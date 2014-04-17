package com.softwaremill.codebrag.cache

import com.typesafe.scalalogging.slf4j.Logging
import com.softwaremill.codebrag.repository.{BranchesSelector, Repository}
import com.softwaremill.codebrag.domain.{BranchState, CommitInfo, MultibranchLoadCommitsResult}
import com.softwaremill.codebrag.service.config.CommitCacheConfig
import com.softwaremill.codebrag.dao.commitinfo.CommitInfoDAO
import com.softwaremill.codebrag.dao.branchsnapshot.BranchStateDAO
import java.util.concurrent.atomic.AtomicReference

/**
 * Keeps commits (SHA) for all repository branches
 */
class BranchCommitsCache(val repository: Repository, backend: PersistentBackendForCache, config: CommitCacheConfig) extends Logging {

  private type BranchCommits = AtomicReference[List[BranchCommitCacheEntry]]

  private val commits = new scala.collection.mutable.HashMap[String, BranchCommits]

  def addCommits(loadResult: MultibranchLoadCommitsResult) {
    backend.persist(loadResult)
    loadResult.commits.foreach { branchCommits =>
      val cacheEntries = branchCommits.commits.map(partialCommitToCacheEntry)
      addCommitsToBranch(cacheEntries, branchCommits.branchName)
    }
  }

  def cleanupStaleBranches() {
    val staleBranches = repository.findStaleBranchesFullNames(getFullBranchNames.toSet)
    logger.debug(s"Purging stale branches from cache ${staleBranches}")
    staleBranches.foreach(commits.remove)
    backend.remove(staleBranches)
  }

  private def addCommitsToBranch(newCommits: List[BranchCommitCacheEntry], branchName: String) {
    commits.get(branchName) match {
      case Some(branchCommits) => branchCommits.set((newCommits ::: branchCommits.get()).take(maxCommitsPerBranchCount))
      case None => commits.put(branchName, new BranchCommits(newCommits.take(maxCommitsPerBranchCount)))
    }
    logger.debug(s"Number of commits in ${branchName}: ${getBranchCommits(branchName).size}")
  }

  def getFullBranchNames = commits.keySet

  def getShortBranchNames = getFullBranchNames.map(_.replace(BranchesSelector.RemoteBranchPrefix, ""))

  def getCheckedOutBranchShortName = repository.getCheckedOutBranchFullName.replace(BranchesSelector.RemoteBranchPrefix, "")

  def getAllCommits = commits.flatten(_._2.get).toSet

  def getBranchCommits(branchName: String): List[BranchCommitCacheEntry] = {
    commits.get(branchName).map(_.get).getOrElse(List.empty[BranchCommitCacheEntry])
  }

  def initialize() {
    logger.debug(s"Initializing repo cache")
    val savedState = backend.loadBranchesState()
    val loadResult = repository.loadLastKnownRepoState(savedState, maxCommitsPerBranchCount)
    loadResult.commits.foreach { branchCommits =>
      val cacheEntries = branchCommits.commits.map(partialCommitToCacheEntry)
      logger.debug(s"Adding ${cacheEntries.size} commits to ${branchCommits.branchName}")
      addCommitsToBranch(cacheEntries, branchCommits.branchName)
    }
    logger.debug(s"Cache initialized")
  }

  private def partialCommitToCacheEntry(commit: CommitInfo): BranchCommitCacheEntry = {
    BranchCommitCacheEntry(commit.sha, commit.authorName, commit.authorEmail, commit.commitDate)
  } 
  
  private def maxCommitsPerBranchCount = config.maxCommitsCachedPerBranch

}

class PersistentBackendForCache(commitInfoDao: CommitInfoDAO, branchStateDao: BranchStateDAO) extends Logging {

  def persist(loadResult: MultibranchLoadCommitsResult) {
    persistUniqueCommits(loadResult)
    persistBranchesState(loadResult)
  }

  def remove(branches: Set[String]) {
    logger.debug(s"Removing branches from DB: ${branches}")
    branchStateDao.removeBranches(branches)
  }


  private def persistUniqueCommits(loadResult: MultibranchLoadCommitsResult) {
    val uniqueCommits = loadResult.uniqueCommits
    logger.debug(s"Persisting cache changes: ${uniqueCommits.size} commits")
    uniqueCommits.foreach(c =>persistCommitSafely(c))
  }

  private def persistCommitSafely(c: CommitInfo): Any = {
    try {
      commitInfoDao.storeCommit(c)
    } catch {
      case e: Exception => logger.warn(s"Could not save commit ${c.sha} in DB. Probably this one already exists - Skipping")
    }
  }

  private def persistBranchesState(loadResult: MultibranchLoadCommitsResult) {
    loadResult.commits.foreach { branch =>
      val state = BranchState(branch.branchName, branch.currentBranchSHA)
      branchStateDao.storeBranchState(state)
      logger.debug(s"Persisted SHA ${branch.currentBranchSHA} for ${branch.branchName}")
    }
  }

  def loadBranchesState(): Map[String, String] = {
    logger.debug("Loading repo state from persistent storage")
    branchStateDao.loadBranchesStateAsMap
  }

}