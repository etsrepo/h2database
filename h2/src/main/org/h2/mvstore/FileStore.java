/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;

import org.h2.mvstore.cache.FilePathCache;
import org.h2.store.fs.FilePath;
import org.h2.store.fs.FilePathCrypt;
import org.h2.store.fs.FilePathNio;

/**
 * The default storage mechanism of the MVStore. This implementation persists
 * data to a file. The file store is responsible to persist data and for free
 * space management.
 */
public class FileStore {

    protected long readCount;
    protected long writeCount;
    
    /**
     * The free spaces between the chunks. The first block to use is block 2
     * (the first two blocks are the store header).
     */
    protected final FreeSpaceBitSet freeSpace = new FreeSpaceBitSet(2, MVStore.BLOCK_SIZE);
    
    protected String fileName;
    protected boolean readOnly;

    protected long fileSize;

    protected FileChannel file;
    protected FileLock fileLock;
    
    @Override
    public String toString() {
        return fileName;
    }
    
    public ByteBuffer readFully(long pos, int len) {
        readCount++;
        ByteBuffer dst = ByteBuffer.allocate(len);
        DataUtils.readFully(file, pos, dst);
        return dst;
    }

    public void writeFully(long pos, ByteBuffer src) {
        writeCount++;
        fileSize = Math.max(fileSize, pos + src.remaining());
        DataUtils.writeFully(file, pos, src);
    }
    
    public void open(String fileName, boolean readOnly, char[] encryptionKey) {
        if (fileName != null && fileName.indexOf(':') < 0) {
            // NIO is used, unless a different file system is specified
            // the following line is to ensure the NIO file system is compiled
            FilePathNio.class.getName();
            fileName = "nio:" + fileName;
        }
        this.fileName = fileName;
        FilePath f = FilePath.get(fileName);
        FilePath parent = f.getParent();
        if (!parent.exists()) {
            throw DataUtils.newIllegalArgumentException("Directory does not exist: {0}", parent);
        }
        if (f.exists() && !f.canWrite()) {
            readOnly = true;
        }
        this.readOnly = readOnly;
        try {
            file = f.open(readOnly ? "r" : "rw");
            if (encryptionKey != null) {
                byte[] password = FilePathCrypt.getPasswordBytes(encryptionKey);
                file = new FilePathCrypt.FileCrypt(fileName, password, file);
            }
            file = FilePathCache.wrap(file);
            fileSize = file.size();
            try {
                if (readOnly) {
                    fileLock = file.tryLock(0, Long.MAX_VALUE, true);
                } else {
                    fileLock = file.tryLock();
                }
            } catch (OverlappingFileLockException e) {
                throw DataUtils.newIllegalStateException(
                        DataUtils.ERROR_FILE_LOCKED, "The file is locked: {0}", fileName, e);
            }
            if (fileLock == null) {
                throw DataUtils.newIllegalStateException(
                        DataUtils.ERROR_FILE_LOCKED, "The file is locked: {0}", fileName);
            }
        } catch (IOException e) {
            throw DataUtils.newIllegalStateException(
                    DataUtils.ERROR_READING_FAILED,
                    "Could not open file {0}", fileName, e);
        }
    }
    
    public void close() {
        try {
            if (fileLock != null) {
                fileLock.release();
                fileLock = null;
            }
            file.close();
            freeSpace.clear();
        } catch (Exception e) {
            throw DataUtils.newIllegalStateException(
                    DataUtils.ERROR_WRITING_FAILED,
                    "Closing failed for file {0}", fileName, e);
        } finally {
            file = null;
        }
    }
    
    public void sync() {
        try {
            file.force(true);
        } catch (IOException e) {
            throw DataUtils.newIllegalStateException(
                    DataUtils.ERROR_WRITING_FAILED,
                    "Could not sync file {0}", fileName, e);
        }
    }
    
    public long size() {
        return fileSize;
    }
    
    public void truncate(long size) {
        try {
            writeCount++;
            file.truncate(size);
            fileSize = Math.min(fileSize, size);
        } catch (IOException e) {
            throw DataUtils.newIllegalStateException(
                    DataUtils.ERROR_WRITING_FAILED,
                    "Could not truncate file {0} to size {1}",
                    fileName, size, e);
        }
    }

    /**
     * Get the file instance in use. The application may read from the file (for
     * example for online backup), but not write to it or truncate it.
     * 
     * @return the file
     */
    public FileChannel getFile() {
        return file;
    }

    /**
     * Get the number of write operations since this store was opened. 
     * For file based stores, this is the number of file write operations.
     * 
     * @return the number of write operations
     */
    public long getWriteCount() {
        return writeCount;
    }
    
    /**
     * Get the number of read operations since this store was opened.
     * For file based stores, this is the number of file read operations.
     * 
     * @return the number of read operations
     */
    public long getReadCount() {
        return readCount;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public int getDefaultRetentionTime() {
        return 45000;
    }

    public void markUsed(long start, int len) {
        freeSpace.markUsed(start, len);
    }
    
    public long allocate(int length) {
        return freeSpace.allocate(length);
    }

    /**
     * Mark the space as free.
     *
     * @param pos the position in bytes
     * @param length the number of bytes
     */
    public void free(long pos, int length) {
        freeSpace.free(pos, length);
    }

    public int getFillRate() {
        return freeSpace.getFillRate();
    }

    public long getFirstFree() {
        return freeSpace.getFirstFree();
    }

    public void clear() {
        freeSpace.clear();
    }

}
