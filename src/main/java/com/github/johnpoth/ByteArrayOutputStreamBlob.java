package com.github.johnpoth;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import com.google.cloud.tools.jib.blob.Blob;
import com.google.cloud.tools.jib.blob.BlobDescriptor;
import com.google.cloud.tools.jib.hash.CountingDigestOutputStream;

public class ByteArrayOutputStreamBlob implements Blob {

    private final ByteArrayOutputStream byteArrayOutputStream;

    public ByteArrayOutputStreamBlob(ByteArrayOutputStream byteArrayOutputStream) {
        this.byteArrayOutputStream = byteArrayOutputStream;
    }

    @Override
    public BlobDescriptor writeTo(OutputStream outputStream) throws IOException {
        CountingDigestOutputStream cdos = new CountingDigestOutputStream(outputStream);
        byteArrayOutputStream.writeTo(cdos);
        return cdos.computeDigest();
    }

    @Override
    public boolean isRetryable() {
        return true;
    }
}
