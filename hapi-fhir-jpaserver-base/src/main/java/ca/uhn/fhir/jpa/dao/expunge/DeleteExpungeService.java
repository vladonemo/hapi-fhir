package ca.uhn.fhir.jpa.dao.expunge;

import ca.uhn.fhir.interceptor.api.HookParams;
import ca.uhn.fhir.interceptor.api.IInterceptorBroadcaster;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.config.DaoConfig;
import ca.uhn.fhir.jpa.api.model.DeleteMethodOutcome;
import ca.uhn.fhir.jpa.dao.data.IResourceLinkDao;
import ca.uhn.fhir.jpa.dao.data.IResourceTableDao;
import ca.uhn.fhir.jpa.model.entity.ResourceLink;
import ca.uhn.fhir.jpa.util.JpaInterceptorBroadcaster;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.PersistenceContextType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
public class DeleteExpungeService {
	private static final Logger ourLog = LoggerFactory.getLogger(DeleteExpungeService.class);

	@Autowired
	protected PlatformTransactionManager myPlatformTransactionManager;
	@PersistenceContext(type = PersistenceContextType.TRANSACTION)
	private EntityManager myEntityManager;
	@Autowired
	private PartitionRunner myPartitionRunner;
	@Autowired
	private ResourceTableFKProvider myResourceTableFKProvider;
	@Autowired
	private IResourceTableDao myResourceTableDao;
	@Autowired
	private IResourceLinkDao myResourceLinkDao;
	@Autowired
	private IInterceptorBroadcaster myInterceptorBroadcaster;
	@Autowired
	private DaoConfig myDaoConfig;

	public DeleteMethodOutcome expungeByResourcePids(String theUrl, String theResourceName, Slice<Long> thePids, RequestDetails theRequest) {
		if (thePids.isEmpty()) {
			return new DeleteMethodOutcome();
		}

		HookParams params = new HookParams()
			.add(RequestDetails.class, theRequest)
			.addIfMatchesType(ServletRequestDetails.class, theRequest)
			.add(String.class, theUrl);
		JpaInterceptorBroadcaster.doCallHooks(myInterceptorBroadcaster, theRequest, Pointcut.STORAGE_PRE_DELETE_EXPUNGE, params);

		TransactionTemplate txTemplate = new TransactionTemplate(myPlatformTransactionManager);
		txTemplate.executeWithoutResult(t -> validateOkToDeleteAndExpunge(thePids));

		ourLog.info("Expunging all records linking to {} resources...", thePids.getNumber());
		AtomicLong expungedEntitiesCount = new AtomicLong();
		AtomicLong expungedResourcesCount = new AtomicLong();
		myPartitionRunner.runInPartitionedThreads(thePids, pidChunk -> deleteInTransaction(theResourceName, pidChunk, expungedResourcesCount, expungedEntitiesCount, theRequest));
		ourLog.info("Expunged a total of {} records", expungedEntitiesCount);

		DeleteMethodOutcome retval = new DeleteMethodOutcome();
		retval.setExpungedResourcesCount(expungedResourcesCount.get());
		retval.setExpungedEntitiesCount(expungedEntitiesCount.get());
		return retval;
	}

	public void validateOkToDeleteAndExpunge(Slice<Long> theAllTargetPids) {
		if (!myDaoConfig.isEnforceReferentialIntegrityOnDelete()) {
			ourLog.info("Referential integrity on delete disabled.  Skipping referential integrity check.");
			return;
		}

		List<ResourceLink> conflictResourceLinks = Collections.synchronizedList(new ArrayList<>());
		myPartitionRunner.runInPartitionedThreads(theAllTargetPids, someTargetPids -> findResourceLinksWithTargetPidIn(theAllTargetPids.getContent(), someTargetPids, conflictResourceLinks));

		if (conflictResourceLinks.isEmpty()) {
			return;
		}

		ResourceLink firstConflict = conflictResourceLinks.get(0);
		String sourceResourceId = firstConflict.getSourceResource().getIdDt().toVersionless().getValue();
		String targetResourceId = firstConflict.getTargetResource().getIdDt().toVersionless().getValue();
		throw new InvalidRequestException("DELETE with _expunge=true failed.  Unable to delete " +
			targetResourceId + " because " + sourceResourceId + " refers to it via the path " + firstConflict.getSourcePath());
	}

	private void findResourceLinksWithTargetPidIn(List<Long> theAllTargetPids, List<Long> theSomeTargetPids, List<ResourceLink> theConflictResourceLinks) {
		// We only need to find one conflict, so if we found one already in an earlier partition run, we can skip the rest of the searches
		if (theConflictResourceLinks.isEmpty()) {
			List<ResourceLink> conflictResourceLinks = myResourceLinkDao.findWithTargetPidIn(theSomeTargetPids).stream()
				// Filter out resource links for which we are planning to delete the source.
				// theAllTargetPids contains a list of all the pids we are planning to delete.  So we only want
				// to consider a link to be a conflict if the source of that link is not in theAllTargetPids.
				.filter(link -> !theAllTargetPids.contains(link.getSourceResourcePid()))
				.collect(Collectors.toList());

			// We do this in two steps to avoid lock contention on this synchronized list
			theConflictResourceLinks.addAll(conflictResourceLinks);
		}
	}

	private void deleteInTransaction(String theResourceName, List<Long> thePidChunk, AtomicLong theExpungedResourcesCount, AtomicLong theExpungedEntitiesCount, RequestDetails theRequest) {
		TransactionTemplate txTemplate = new TransactionTemplate(myPlatformTransactionManager);
		txTemplate.executeWithoutResult(t -> deleteAllRecordsLinkingTo(theResourceName, thePidChunk, theExpungedResourcesCount, theExpungedEntitiesCount, theRequest));
	}

	private void deleteAllRecordsLinkingTo(String theResourceName, List<Long> thePids, AtomicLong theExpungedResourcesCount, AtomicLong theExpungedEntitiesCount, RequestDetails theRequest) {
		HookParams params = new HookParams()
			.add(String.class, theResourceName)
			.add(List.class, thePids)
			.add(AtomicLong.class, theExpungedEntitiesCount)
			.add(RequestDetails.class, theRequest)
			.addIfMatchesType(ServletRequestDetails.class, theRequest);
		JpaInterceptorBroadcaster.doCallHooks(myInterceptorBroadcaster, theRequest, Pointcut.STORAGE_DELETE_EXPUNGE_PID_LIST, params);

		String pidListString = thePids.toString().replace("[", "(").replace("]", ")");
		List<ResourceForeignKey> resourceForeignKeys = myResourceTableFKProvider.getResourceForeignKeys();

		for (ResourceForeignKey resourceForeignKey : resourceForeignKeys) {
			deleteRecordsByColumn(pidListString, resourceForeignKey, theExpungedEntitiesCount);
		}

		// Lastly we need to delete records from the resource table all of these other tables link to:
		ResourceForeignKey resourceTablePk = new ResourceForeignKey("HFJ_RESOURCE", "RES_ID");
		int entitiesDeleted = deleteRecordsByColumn(pidListString, resourceTablePk, theExpungedEntitiesCount);
		theExpungedResourcesCount.addAndGet(entitiesDeleted);
	}

	private int deleteRecordsByColumn(String thePidListString, ResourceForeignKey theResourceForeignKey, AtomicLong theExpungedEntitiesCount) {
		int entitesDeleted = myEntityManager.createNativeQuery("DELETE FROM " + theResourceForeignKey.table + " WHERE " + theResourceForeignKey.key + " IN " + thePidListString).executeUpdate();
		ourLog.info("Expunged {} records from {}", entitesDeleted, theResourceForeignKey.table);
		theExpungedEntitiesCount.addAndGet(entitesDeleted);
		return entitesDeleted;
	}
}
