package com.carddemo.xfer.posting;

/** XFERFEE 9999-ABEND-PROGRAM: the message is the DISPLAY that precedes the abend. */
public class PostingAbend extends RuntimeException {

    public PostingAbend(String message) {
        super(message);
    }
}
