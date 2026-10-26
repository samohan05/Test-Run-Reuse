package com.intland.codebeamer.wiki.plugins.streamreuse;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.intland.codebeamer.manager.AssociationManager;
import com.intland.codebeamer.manager.TrackerItemManager;
import com.intland.codebeamer.persistence.dao.TrackerItemDao;
import com.intland.codebeamer.persistence.dto.AssociationDto;
import com.intland.codebeamer.persistence.dto.AssociationTypeDto;
import com.intland.codebeamer.persistence.dto.TrackerDto;
import com.intland.codebeamer.persistence.dto.TrackerItemDto;
import com.intland.codebeamer.persistence.dto.TrackerItemReferenceWrapperDto;
import com.intland.codebeamer.persistence.dto.UserDto;

/**
 * Creates one target Test Run in an independent transaction.
 */
@Component
public class StreamReuseCopyService {

    private final TrackerItemManager trackerItemManager;
    private final TrackerItemDao trackerItemDao;
    private final AssociationManager associationManager;

    public StreamReuseCopyService(
            TrackerItemManager trackerItemManager,
            TrackerItemDao trackerItemDao,
            AssociationManager associationManager) {

        this.trackerItemManager = trackerItemManager;
        this.trackerItemDao = trackerItemDao;
        this.associationManager = associationManager;
    }

    /**
     * The Codebeamer manager methods used below declare checked access,
     * veto and timeout exceptions in Codebeamer 3.2.0.2. Declaring
     * Exception here allows Spring to roll back for every checked and
     * unchecked failure, while StreamReuseJob records the item failure.
     */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public Integer copy(
            UserDto user,
            TrackerDto targetRunTracker,
            StreamReusePlanner.Work work,
            String operationId) throws Exception {

        TrackerItemDto copiedRun =
                new TrackerItemDto(work.sourceRun);

        copiedRun.setTracker(targetRunTracker);

        copiedRun.setTableReferenceCell(
                0,
                0,
                0,
                new TrackerItemReferenceWrapperDto(
                        work.targetTestCase));

        copiedRun.setChoiceList(
                work.evidence.targetChoiceIndex,
                work.evidence.values);

        trackerItemManager.create(
                user,
                copiedRun);

        TrackerItemDto persistedRun =
                trackerItemDao.findById(copiedRun.getId());

        if (persistedRun == null) {
            throw new IllegalStateException(
                    "Copied Test Run " + copiedRun.getId() +
                            " could not be reloaded.");
        }

        List<?> persistedEvidence =
                persistedRun.getChoiceList(
                        work.evidence.targetChoiceIndex);

        if (persistedEvidence == null ||
                persistedEvidence.isEmpty()) {

            throw new IllegalStateException(
                    "Test Run Evidence was not persisted for copied run " +
                            copiedRun.getId() + ".");
        }

        AssociationDto<TrackerItemDto, TrackerItemDto> association =
                new AssociationDto<>(
                        persistedRun,
                        work.sourceRun);

        association.setType(AssociationTypeDto.COPY_OF);
        association.setSubmitter(user);
        association.setDescription(
                "Stream bulk reuse operation " + operationId);

        associationManager.create(
                user,
                association,
                null);

        return copiedRun.getId();
    }
}
