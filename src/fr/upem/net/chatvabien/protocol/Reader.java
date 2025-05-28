package fr.upem.net.chatvabien.protocol;

import java.nio.ByteBuffer;

/**
 * Represents a generic reader that can process and extract objects of type {@code T}
 * from a {@link ByteBuffer} in a stepwise manner.
 *
 * @param <T> the type of object to be read
 */
public interface Reader<T> {

    /**
     * Represents the status of a reading process.
     */
    public static enum ProcessStatus {
        /** The object has been fully read and is ready to be retrieved. */
        DONE,
        /** More data is needed to complete the reading process. */
        REFILL,
        /** An error occurred during the reading process. */
        ERROR
    };

    /**
     * Processes bytes from the given {@link ByteBuffer} to read an object of type {@code T}.
     *
     * @param bb the {@link ByteBuffer} containing data to be read
     * @return the current status of the reading process:
     *         <ul>
     *             <li>{@link ProcessStatus#DONE} if the object has been fully read,</li>
     *             <li>{@link ProcessStatus#REFILL} if more data is needed,</li>
     *             <li>{@link ProcessStatus#ERROR} if an error occurred.</li>
     *         </ul>
     * @throws IllegalStateException if called when the reader is already in the DONE or ERROR state
     */
    public ProcessStatus process(ByteBuffer bb);

    /**
     * Retrieves the object of type {@code T} that was read.
     *
     * @return the read object
     * @throws IllegalStateException if the object is not ready or if an error occurred
     */
    public T get();

    /**
     * Resets the reader, clearing any internal state and making it ready to read another object.
     */
    public void reset();

}
