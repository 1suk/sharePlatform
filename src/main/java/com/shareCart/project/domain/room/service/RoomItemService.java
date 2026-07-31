package com.shareCart.project.domain.room.service;

public interface RoomItemService {
    void updateItemDetails(Long roomId, Long roomItemId, String email, String unit, Integer totalQty);
}
