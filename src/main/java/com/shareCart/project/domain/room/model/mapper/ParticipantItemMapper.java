package com.shareCart.project.domain.room.model.mapper;

import com.shareCart.project.domain.room.model.vo.ParticipantItemVO;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.data.repository.query.Param;

import java.util.List;

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

    List<ParticipantItemVO> findByItemId(Long itemId);

    void upsertAllocationBatch(@Param("list") List<ParticipantItemVO> allocations);
}
