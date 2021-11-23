package com.github.johnpoth;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.events.TransferEvent;
import org.apache.maven.wagon.events.TransferEventSupport;
import org.apache.maven.wagon.resource.Resource;

public class FilterProgressOutputStream extends FilterOutputStream {

    private final TransferEventSupport transferEventSupport;
    private final Wagon wagon;
    private final Resource resource;
    private final int eventType;
    private final int requestType;

    public FilterProgressOutputStream(OutputStream out, TransferEventSupport transferEventSupport, Wagon wagon, Resource resource, int eventType, int requestType) {
        super(out);
        this.transferEventSupport = transferEventSupport;
        this.wagon = wagon;
        this.resource = resource;
        this.eventType = eventType;
        this.requestType = requestType;
    }

    @Override
    public void write(byte b[], int off, int len) throws IOException {
        super.write(b, off, len);
        if (off == 0) {
            TransferEvent event = new TransferEvent(this.wagon, this.resource, this.eventType, this.requestType);
            this.transferEventSupport.fireTransferProgress(event, b, len);
        } else {
            byte[] bytes = new byte[len];
            System.arraycopy(b, off, bytes, 0, len);
            TransferEvent event = new TransferEvent(this.wagon, this.resource, this.eventType, this.requestType);
            this.transferEventSupport.fireTransferProgress(event, bytes, len);
        }
    }
}
