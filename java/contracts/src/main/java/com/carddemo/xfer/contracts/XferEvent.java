package com.carddemo.xfer.contracts;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Every message on the xfer topics; {@code runId} identifies one business day replay. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = DailyTranReceived.class, name = "DailyTranReceived"),
    @JsonSubTypes.Type(value = EndOfDay.class, name = "EndOfDay"),
    @JsonSubTypes.Type(value = TransferRequested.class, name = "TransferRequested"),
    @JsonSubTypes.Type(value = ExtractClosed.class, name = "ExtractClosed"),
    @JsonSubTypes.Type(value = TransferPosted.class, name = "TransferPosted"),
    @JsonSubTypes.Type(value = TransferRejected.class, name = "TransferRejected"),
    @JsonSubTypes.Type(value = BatchPosted.class, name = "BatchPosted"),
})
public sealed interface XferEvent
        permits DailyTranReceived, EndOfDay, TransferRequested, ExtractClosed,
                TransferPosted, TransferRejected, BatchPosted {

    String runId();
}
