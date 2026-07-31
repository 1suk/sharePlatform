package com.shareCart.project.domain.room.model.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.springframework.data.repository.query.Param;

@Mapper
public interface ParticipantItemMapper {
    int upsertAllocation(
            @Param("participantId") Long participantId,
            @Param("itemId") Long itemId,
            @Param("quantity") Integer quantity
    );

    Integer sumAllocatedByItemExcludingParticipant(
            @Param("itemId") Long itemId,
            @Param("participantId") Long participantId
    );
}
