/*
 * Copyright (2021) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.delta

import java.io.FileNotFoundException

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

// scalastyle:off import.ordering.noEmptyLine

import com.databricks.spark.util.TagDefinitions.TAG_ASYNC
import org.apache.spark.sql.delta.sources.DeltaSQLConf
import org.apache.spark.sql.delta.util.FileNames._
import org.apache.hadoop.fs.{FileStatus, Path}

import org.apache.spark.{SparkContext, SparkException}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.execution.SQLExecution
import org.apache.spark.util.ThreadUtils

/**
 * Manages the creation, computation, and access of Snapshot's for Delta tables. Responsibilities
 * include:
 *  - Figuring out the set of files that are required to compute a specific version of a table
 *  - Updating and exposing the latest snapshot of the Delta table in a thread-safe manner
 */
trait SnapshotManagement { self: DeltaLog =>
  import SnapshotManagement.verifyDeltaVersions

  @volatile private[delta] var asyncUpdateTask: Future[Unit] = _

  /** The timestamp when the last successful update action is finished. */
  @volatile protected var lastUpdateTimestamp: Long = -1L

  @volatile protected var currentSnapshot: Snapshot = getSnapshotAtInit

  /**
   * Get the LogSegment that will help in computing the Snapshot of the table at DeltaLog
   * initialization.
   *
   * @param startingCheckpoint A checkpoint that we can start our listing from
   */
  protected def getLogSegmentFrom(
      startingCheckpoint: Option[CheckpointMetaData]): LogSegment = {
    getLogSegmentForVersion(startingCheckpoint.map(_.version))
  }

  /** Get an iterator of files in the _delta_log directory starting with the startVersion. */
  protected def listFrom(startVersion: Long): Iterator[FileStatus] = {
    store.listFrom(checkpointPrefix(logPath, startVersion), newDeltaHadoopConf())
  }

  /** Returns true if the path is delta log files. Delta log files can be delta commit file
   * (e.g., 000000000.json), or checkpoint file. (e.g., 000000001.checkpoint.00001.00003.parquet)
   * @param path Path of a file
   * @return Boolean Whether the file is delta log files
   */
  protected def isDeltaCommitOrCheckpointFile(path: Path): Boolean = {
    isCheckpointFile(path) || isDeltaFile(path)
  }

  /**
   * Returns the delta files and checkpoint files starting from the given `startVersion`.
   * `versionToLoad` is an optional parameter to set the max bound. It's usually used to load a
   * table snapshot for a specific version.
   *
   * @param startVersion the version to start. Inclusive.
   * @param versionToLoad the optional parameter to set the max version we should return. Inclusive.
   */
  private final def listDeltaAndCheckpointFiles(
      startVersion: Long,
      versionToLoad: Option[Long]): Array[FileStatus] = {
    listFrom(startVersion)
      // Pick up all checkpoint and delta files
      .filter { file => isDeltaCommitOrCheckpointFile(file.getPath) }
      // Checkpoint files of 0 size are invalid but Spark will ignore them silently when reading
      // such files, hence we drop them so that we never pick up such checkpoints.
      .filterNot { file => isCheckpointFile(file.getPath) && file.getLen == 0 }
      // take files until the version we want to load
      .takeWhile(f => versionToLoad.forall(v => getFileVersion(f.getPath) <= v))
      .toArray
  }

  /**
   * Get a list of files that can be used to compute a Snapshot at version `versionToLoad`, If
   * `versionToLoad` is not provided, will generate the list of files that are needed to load the
   * latest version of the Delta table. This method also performs checks to ensure that the delta
   * files are contiguous.
   *
   * @param startCheckpoint A potential start version to perform the listing of the DeltaLog,
   *                        typically that of a known checkpoint. If this version's not provided,
   *                        we will start listing from version 0.
   * @param versionToLoad A specific version to load. Typically used with time travel and the
   *                      Delta streaming source. If not provided, we will try to load the latest
   *                      version of the table.
   * @return Some LogSegment to build a Snapshot if files do exist after the given
   *         startCheckpoint. None, if there are no new files after `startCheckpoint`.
   */
  protected def getLogSegmentForVersion(
      startCheckpoint: Option[Long],
      versionToLoad: Option[Long] = None): LogSegment = {
    recordFrameProfile("Delta", "SnapshotManagement.getLogSegmentForVersion") {
      // List from the starting checkpoint. If a checkpoint doesn't exist, this will still return
      // deltaVersion=0.
      val newFiles = listDeltaAndCheckpointFiles(startCheckpoint.getOrElse(0L), versionToLoad)
      if (newFiles.isEmpty && startCheckpoint.isEmpty) {
        throw DeltaErrors.emptyDirectoryException(logPath.toString)
      } else if (newFiles.isEmpty) {
        // The directory may be deleted and recreated and we may have stale state in our DeltaLog
        // singleton, so try listing from the first version
        return getLogSegmentForVersion(None, versionToLoad)
      }
      val (checkpoints, deltas) = newFiles.partition(f => isCheckpointFile(f.getPath))

      // Find the latest checkpoint in the listing that is not older than the versionToLoad
      val lastChkpoint = versionToLoad.map(CheckpointInstance(_, None))
        .getOrElse(CheckpointInstance.MaxValue)
      val checkpointFiles = checkpoints.map(f => CheckpointInstance(f.getPath))
      val newCheckpoint = getLatestCompleteCheckpointFromList(checkpointFiles, lastChkpoint)
      val newCheckpointVersion = newCheckpoint.map(_.version).getOrElse {
        // If we do not have any checkpoint, pass new checkpoint version as -1 so that first
        // delta version can be 0.
        if (startCheckpoint.isDefined) {
          // `startCheckpoint` was given but no checkpoint found on delta log. This means that the
          // last checkpoint we thought should exist (the `_last_checkpoint` file) no longer exists.
          // Try to look up another valid checkpoint and create `LogSegment` from it.
          recordDeltaEvent(this, "delta.checkpoint.error.partial")
          val alternativeLogSegment = getLogSegmentWithMaxExclusiveCheckpointVersion(
            snapshotVersion = versionToLoad.getOrElse(deltaVersion(deltas.last.getPath)),
            startCheckpoint.get)
          return alternativeLogSegment.getOrElse {
            throw DeltaErrors.missingPartFilesException(
              startCheckpoint.get, new FileNotFoundException(
                s"Checkpoint file to load version: ${startCheckpoint.get} is missing."))
          }
        }
        -1L
      }

      // If there is a new checkpoint, start new lineage there. If `newCheckpointVersion` is -1,
      // it will list all existing delta files.
      val deltasAfterCheckpoint = deltas.filter { file =>
        deltaVersion(file.getPath) > newCheckpointVersion
      }

      val deltaVersions = deltasAfterCheckpoint.map(f => deltaVersion(f.getPath))
      // We may just be getting a checkpoint file after the filtering
      if (deltaVersions.nonEmpty) {
        if (deltaVersions.head != newCheckpointVersion + 1) {
          throw DeltaErrors.logFileNotFoundException(
            deltaFile(logPath, newCheckpointVersion + 1), deltaVersions.last, metadata)
        }
        verifyDeltaVersions(spark, deltaVersions, Some(newCheckpointVersion + 1), versionToLoad)
      }

      val newVersion = deltaVersions.lastOption.getOrElse(newCheckpoint.get.version)
      val newCheckpointFiles: Seq[FileStatus] = newCheckpoint.map { newCheckpoint =>
        val newCheckpointPaths = newCheckpoint.getCorrespondingFiles(logPath).toSet
        val newCheckpointFileArray = checkpoints.filter(f => newCheckpointPaths.contains(f.getPath))
        assert(newCheckpointFileArray.length == newCheckpointPaths.size,
          "Failed in getting the file information for:\n" +
            newCheckpointPaths.mkString(" -", "\n -", "") + "\n" +
            "among\n" + checkpoints.map(_.getPath).mkString(" -", "\n -", ""))
        newCheckpointFileArray.toSeq
      }.getOrElse(Nil)

      // In the case where `deltasAfterCheckpoint` is empty, `deltas` should still not be empty,
      // they may just be before the checkpoint version unless we have a bug in log cleanup.
      if (deltas.isEmpty) {
        throw new IllegalStateException(s"Could not find any delta files for version $newVersion")
      }
      if (versionToLoad.exists(_ != newVersion)) {
        throw new IllegalStateException(
          s"Trying to load a non-existent version ${versionToLoad.get}")
      }
      val lastCommitTimestamp = deltas.last.getModificationTime

      LogSegment(
        logPath,
        newVersion,
        deltasAfterCheckpoint,
        newCheckpointFiles,
        newCheckpoint.map(_.version),
        lastCommitTimestamp)
    }
  }

  /**
   * Load the Snapshot for this Delta table at initialization. This method uses the `lastCheckpoint`
   * file as a hint on where to start listing the transaction log directory. If the _delta_log
   * directory doesn't exist, this method will return an `InitialSnapshot`.
   */
  protected def getSnapshotAtInit: Snapshot = {
    recordFrameProfile("Delta", "SnapshotManagement.getSnapshotAtInit") {
      try {
        val segment = getLogSegmentFrom(lastCheckpoint)
        val startCheckpoint = segment.checkpointVersionOpt
          .map(v => s" starting from checkpoint $v.").getOrElse(".")
        logInfo(s"Loading version ${segment.version}$startCheckpoint")
        val snapshot = createSnapshot(segment, minFileRetentionTimestamp)

        lastUpdateTimestamp = clock.getTimeMillis()
        logInfo(s"Returning initial snapshot $snapshot")
        snapshot
      } catch {
        case e: FileNotFoundException =>
          logInfo(s"Creating initial snapshot without metadata, because the directory is empty")
          // The log directory may not exist
          new InitialSnapshot(logPath, this)
      }
    }
  }

  /** Returns the current snapshot. Note this does not automatically `update()`. */
  def snapshot: Snapshot = currentSnapshot

  protected def createSnapshot(
      initSegment: LogSegment,
      minFileRetentionTimestamp: Long): Snapshot = {
    val checksumOpt = readChecksum(initSegment.version)
    createSnapshotFromGivenOrEquivalentLogSegment(initSegment) { segment =>
      new Snapshot(
        logPath,
        segment.version,
        segment,
        minFileRetentionTimestamp,
        this,
        segment.lastCommitTimestamp,
        checksumOpt)
    }
  }

  /**
   * Returns a [[LogSegment]] for reading `snapshotVersion` such that the segment's checkpoint
   * version (if checkpoint present) is LESS THAN `maxExclusiveCheckpointVersion`.
   * This is useful when trying to skip a bad checkpoint. Returns `None` when we are not able to
   * construct such [[LogSegment]], for example, no checkpoint can be used but we don't have the
   * entire history from version 0 to version `snapshotVersion`.
   */
  private def getLogSegmentWithMaxExclusiveCheckpointVersion(
      snapshotVersion: Long,
      maxExclusiveCheckpointVersion: Long): Option[LogSegment] = {
    assert(
      snapshotVersion >= maxExclusiveCheckpointVersion,
      s"snapshotVersion($snapshotVersion) is less than " +
        s"maxExclusiveCheckpointVersion($maxExclusiveCheckpointVersion)")
    val largestCheckpointVersionToSearch = snapshotVersion.min(maxExclusiveCheckpointVersion - 1)
    val previousCp = if (largestCheckpointVersionToSearch < 0) {
      None
    } else {
      findLastCompleteCheckpoint(
        // The largest possible `CheckpointInstance` at version `largestCheckpointVersionToSearch`
        CheckpointInstance(largestCheckpointVersionToSearch, numParts = Some(Int.MaxValue)))
    }
    previousCp match {
      case Some(cp) =>
        val filesSinceCheckpointVersion = listDeltaAndCheckpointFiles(
          startVersion = cp.version,
          versionToLoad = Some(snapshotVersion))
        val (checkpoints, deltas) =
          filesSinceCheckpointVersion.partition(f => isCheckpointFile(f.getPath))
        if (deltas.isEmpty) {
          // We cannot find any delta files. Returns None as we cannot construct a `LogSegment` only
          // from checkpoint files. This is because in order to create a `LogSegment`, we need to
          // set `LogSegment.lastCommitTimestamp`, and it must be read from the file modification
          // time of the delta file for `snapshotVersion`. It cannot be the file modification time
          // of a checkpoint file because it should be deterministic regardless how we construct the
          // Snapshot, and only delta json log files can ensure that.
          return None
        }
        // `checkpoints` may contain multiple checkpoints for different part sizes, we need to
        // search `FileStatus`s of the checkpoint files for `cp`.
        val checkpointFileNames = cp.getCorrespondingFiles(logPath).map(_.getName).toSet
        val newCheckpointFiles =
          checkpoints.filter(f => checkpointFileNames.contains(f.getPath.getName))
        assert(newCheckpointFiles.length == checkpointFileNames.size,
          "Failed in getting the file information for:\n" +
            checkpointFileNames.mkString(" -", "\n -", "") + "\n" +
            "among\n" + checkpoints.map(_.getPath).mkString(" -", "\n -", ""))
        // Create the list of `FileStatus`s for delta files after `cp.version`.
        val deltasAfterCheckpoint = deltas.filter { file =>
          deltaVersion(file.getPath) > cp.version
        }
        val deltaVersions = deltasAfterCheckpoint.map(f => deltaVersion(f.getPath))
        // `deltaVersions` should not be empty and `verifyDeltaVersions` will verify it
        try {
          verifyDeltaVersions(spark, deltaVersions, Some(cp.version + 1), Some(snapshotVersion))
        } catch {
          case NonFatal(e) =>
            logWarning(s"Failed to find a valid LogSegment for $snapshotVersion", e)
            return None
        }
        Some(LogSegment(
          logPath,
          snapshotVersion,
          deltas,
          newCheckpointFiles,
          Some(cp.version),
          deltas.last.getModificationTime))
      case None =>
        val deltas =
          listDeltaAndCheckpointFiles(startVersion = 0, versionToLoad = Some(snapshotVersion))
            .filter(file => isDeltaFile(file.getPath))
        val deltaVersions = deltas.map(f => deltaVersion(f.getPath))
        try {
          verifyDeltaVersions(spark, deltaVersions, Some(0), Some(snapshotVersion))
        } catch {
          case NonFatal(e) =>
            logWarning(s"Failed to find a valid LogSegment for $snapshotVersion", e)
            return None
        }
        Some(LogSegment(
          logPath = logPath,
          version = snapshotVersion,
          deltas = deltas,
          checkpoint = Nil,
          checkpointVersionOpt = None,
          lastCommitTimestamp = deltas.last.getModificationTime))
    }
  }

  /**
   * Create a [[Snapshot]] from the given [[LogSegment]]. If failing to create the snapshot, we will
   * search an equivalent [[LogSegment]] using a different checkpoint and retry up to
   * [[DeltaSQLConf.DELTA_SNAPSHOT_LOADING_MAX_RETRIES]] times.
   */
  protected def createSnapshotFromGivenOrEquivalentLogSegment(
      initSegment: LogSegment)(snapshotCreator: LogSegment => Snapshot): Snapshot = {
    val numRetries =
      spark.sessionState.conf.getConf(DeltaSQLConf.DELTA_SNAPSHOT_LOADING_MAX_RETRIES)
    var attempt = 0
    var segment = initSegment
    // Remember the first error we hit. If all retries fail, we will throw the first error to
    // provide the root cause. We catch `SparkException` because corrupt checkpoint files are
    // detected in the executor side when a task is trying to read them.
    var firstError: SparkException = null
    while (true) {
      try {
        return snapshotCreator(segment)
      } catch {
        case e: SparkException if attempt < numRetries && segment.checkpointVersionOpt.nonEmpty =>
          if (firstError == null) {
            firstError = e
          }
          logWarning(s"Failed to create a snapshot from log segment: $segment. " +
            s"Trying a different checkpoint.", e)
          segment = getLogSegmentWithMaxExclusiveCheckpointVersion(
            segment.version,
            segment.checkpointVersionOpt.get).getOrElse {
              // Throw the first error if we cannot find an equivalent `LogSegment`.
              throw firstError
            }
          attempt += 1
        case e: SparkException if firstError != null =>
          logWarning(s"Failed to create a snapshot from log segment: $segment", e)
          throw firstError
      }
    }
    throw new IllegalStateException("should not happen")
  }

  /** Checks if the snapshot of the table has surpassed our allowed staleness. */
  private def isSnapshotStale: Boolean = {
    val stalenessLimit = spark.sessionState.conf.getConf(
      DeltaSQLConf.DELTA_ASYNC_UPDATE_STALENESS_TIME_LIMIT)
    stalenessLimit == 0L || lastUpdateTimestamp < 0 ||
      clock.getTimeMillis() - lastUpdateTimestamp >= stalenessLimit
  }

  /**
   * Update ActionLog by applying the new delta files if any.
   *
   * @param stalenessAcceptable Whether we can accept working with a stale version of the table. If
   *                            the table has surpassed our staleness tolerance, we will update to
   *                            the latest state of the table synchronously. If staleness is
   *                            acceptable, and the table hasn't passed the staleness tolerance, we
   *                            will kick off a job in the background to update the table state,
   *                            and can return a stale snapshot in the meantime.
   */
  def update(stalenessAcceptable: Boolean = false): Snapshot = {
    val doAsync = stalenessAcceptable && !isSnapshotStale
    if (!doAsync) {
      recordFrameProfile("Delta", "SnapshotManagement.update") {
        lockInterruptibly {
          updateInternal(isAsync = false)
        }
      }
    } else {
      if (asyncUpdateTask == null || asyncUpdateTask.isCompleted) {
        val jobGroup = spark.sparkContext.getLocalProperty(SparkContext.SPARK_JOB_GROUP_ID)
        asyncUpdateTask = Future[Unit] {
          spark.sparkContext.setLocalProperty("spark.scheduler.pool", "deltaStateUpdatePool")
          spark.sparkContext.setJobGroup(
            jobGroup,
            s"Updating state of Delta table at ${currentSnapshot.path}",
            interruptOnCancel = true)
          tryUpdate(isAsync = true)
        }(SnapshotManagement.deltaLogAsyncUpdateThreadPool)
      }
      currentSnapshot
    }
  }

  /**
   * Try to update ActionLog. If another thread is updating ActionLog, then this method returns
   * at once and return the current snapshot. The return snapshot may be stale.
   */
  private def tryUpdate(isAsync: Boolean): Snapshot = {
    if (deltaLogLock.tryLock()) {
      try {
        updateInternal(isAsync)
      } finally {
        deltaLogLock.unlock()
      }
    } else {
      currentSnapshot
    }
  }

  /**
   * Queries the store for new delta files and applies them to the current state.
   * Note: the caller should hold `deltaLogLock` before calling this method.
   */
  protected def updateInternal(isAsync: Boolean): Snapshot =
    recordDeltaOperation(this, "delta.log.update", Map(TAG_ASYNC -> isAsync.toString)) {
      try {
        val segment = getLogSegmentForVersion(currentSnapshot.logSegment.checkpointVersionOpt)
        if (segment == currentSnapshot.logSegment) {
          // Exit early if there is no new file
          lastUpdateTimestamp = clock.getTimeMillis()
          return currentSnapshot
        }

        val startingFrom = segment.checkpointVersionOpt
          .map(v => s" starting from checkpoint version $v.").getOrElse(".")
        logInfo(s"Loading version ${segment.version}$startingFrom")

        val newSnapshot = createSnapshot(segment, minFileRetentionTimestamp)

        if (currentSnapshot.version > -1 &&
          currentSnapshot.metadata.id != newSnapshot.metadata.id) {
          val msg = s"Change in the table id detected while updating snapshot. " +
            s"\nPrevious snapshot = $currentSnapshot\nNew snapshot = $newSnapshot."
          logError(msg)
          recordDeltaEvent(self, "delta.metadataCheck.update", data = Map(
            "prevSnapshotVersion" -> currentSnapshot.version,
            "prevSnapshotMetadata" -> currentSnapshot.metadata,
            "nextSnapshotVersion" -> newSnapshot.version,
            "nextSnapshotMetadata" -> newSnapshot.metadata))
        }

        replaceSnapshot(newSnapshot)
        logInfo(s"Updated snapshot to $newSnapshot")
      } catch {
        case e: FileNotFoundException =>
          if (Option(e.getMessage).exists(_.contains("reconstruct state at version"))) {
            throw e
          }
          val message = s"No delta log found for the Delta table at $logPath"
          logInfo(message)
          replaceSnapshot(new InitialSnapshot(logPath, this))
      }
      lastUpdateTimestamp = clock.getTimeMillis()
      currentSnapshot
    }

  /** Replace the given snapshot with the provided one. */
  protected def replaceSnapshot(newSnapshot: Snapshot): Unit = {
    if (!deltaLogLock.isHeldByCurrentThread) {
      recordDeltaEvent(this, "delta.update.unsafeReplace")
    }
    currentSnapshot.uncache()
    currentSnapshot = newSnapshot
  }

  /** Get the snapshot at `version`. */
  def getSnapshotAt(
      version: Long,
      commitTimestamp: Option[Long] = None,
      lastCheckpointHint: Option[CheckpointInstance] = None): Snapshot = {
    val current = snapshot
    if (current.version == version) {
      return current
    }

    // Do not use the hint if the version we're asking for is smaller than the last checkpoint hint
    val startingCheckpoint = lastCheckpointHint.collect { case ci if ci.version <= version => ci }
      .orElse(findLastCompleteCheckpoint(CheckpointInstance(version, None)))
    val segment = getLogSegmentForVersion(startingCheckpoint.map(_.version), Some(version))

    createSnapshot(segment, minFileRetentionTimestamp)
  }
}

object SnapshotManagement {
  protected lazy val deltaLogAsyncUpdateThreadPool = {
    val tpe = ThreadUtils.newDaemonCachedThreadPool("delta-state-update", 8)
    ExecutionContext.fromExecutorService(tpe)
  }

  /**
   * - Verify the versions are contiguous.
   * - Verify the versions start with `expectedStartVersion` if it's specified.
   * - Verify the versions end with `expectedEndVersion` if it's specified.
   */
  def verifyDeltaVersions(
      spark: SparkSession,
      versions: Array[Long],
      expectedStartVersion: Option[Long],
      expectedEndVersion: Option[Long]): Unit = {
    if (versions.nonEmpty) {
      // Turn this to a vector so that we can compare it with a range.
      val deltaVersions = versions.toVector
      if ((deltaVersions.head to deltaVersions.last) != deltaVersions) {
        throw DeltaErrors.deltaVersionsNotContiguousException(spark, deltaVersions)
      }
    }
    expectedStartVersion.foreach { v =>
      require(versions.nonEmpty && versions.head == v, "Did not get the first delta " +
        s"file version: $v to compute Snapshot")
    }
    expectedEndVersion.foreach { v =>
      require(versions.nonEmpty && versions.last == v, "Did not get the first delta " +
        s"file version: $v to compute Snapshot")
    }
  }
}

/**
 * Provides information around which files in the transaction log need to be read to create
 * the given version of the log.
 * @param logPath The path to the _delta_log directory
 * @param version The Snapshot version to generate
 * @param deltas The delta commit files (.json) to read
 * @param checkpoint The checkpoint file to read
 * @param checkpointVersionOpt The checkpoint version used to start replay
 * @param lastCommitTimestamp The "unadjusted" timestamp of the last commit within this segment. By
 *                            unadjusted, we mean that the commit timestamps may not necessarily be
 *                            monotonically increasing for the commits within this segment.
 */
case class LogSegment(
    logPath: Path,
    version: Long,
    deltas: Seq[FileStatus],
    checkpoint: Seq[FileStatus],
    checkpointVersionOpt: Option[Long],
    lastCommitTimestamp: Long) {

  override def hashCode(): Int = logPath.hashCode() * 31 + (lastCommitTimestamp % 10000).toInt

  /**
   * An efficient way to check if a cached Snapshot's contents actually correspond to a new
   * segment returned through file listing.
   */
  override def equals(obj: Any): Boolean = {
    obj match {
      case other: LogSegment =>
        version == other.version && lastCommitTimestamp == other.lastCommitTimestamp &&
          logPath == other.logPath
      case _ => false
    }
  }
}

object LogSegment {
  /** The LogSegment for an empty transaction log directory. */
  def empty(path: Path): LogSegment = LogSegment(
    logPath = path,
    version = -1L,
    deltas = Nil,
    checkpoint = Nil,
    checkpointVersionOpt = None,
    lastCommitTimestamp = -1L)
}