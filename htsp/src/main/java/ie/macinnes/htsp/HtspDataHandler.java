/*
 * Copyright (c) 2017 Kiall Mac Innes <kiall@macinnes.ie>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ie.macinnes.htsp;

import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * Default implementation tying together most of the other HTSP classes
 */
public class HtspDataHandler implements HtspConnection.Reader, HtspConnection.Writer, HtspConnection.Listener {

    private static final String TAG = HtspDataHandler.class.getSimpleName();

    private final HtspMessageSerializer mMessageSerializer;
    private final HtspMessage.DispatcherInternal mMessageDispatcher;

    private final ByteBuffer mReadBuffer = ByteBuffer.allocateDirect(5242880); // 5MB
    private final ByteBuffer mWriteBuffer = ByteBuffer.allocateDirect(1024 * 1024); // 1024 * 1024 = Max TVH will accept

    public HtspDataHandler(HtspMessageSerializer messageSerializer, HtspMessage.DispatcherInternal messageDispatcher) {
        mMessageSerializer = messageSerializer;
        mMessageDispatcher = messageDispatcher;
    }

    // HtspConnection.Listener Methods
    @Override
    public Handler getHandler() {
        return null;
    }

    @Override
    public void setConnection(@NonNull HtspConnection connection) {
    }

    @Override
    public void onConnectionStateChange(@NonNull HtspConnection.State state) {
        // Clear buffers out etc as we close the connection
        if (state == HtspConnection.State.CLOSED) {
            // TODO..
        }
    }

    // HtspConnection.Reader Methods

    /**
     * Data read off the connection is passed here, the HtspMessageSerializer is used to build
     * HtspMessage  instances which are given to the HtspMessageDispatcher for handling
     *
     * @param socketChannel The SocketChannel from which to read data
     * @return true on success, false on error
     */
    @Override
    public boolean read(@NonNull SocketChannel socketChannel) {
        int bufferStartPosition = mReadBuffer.position();
        int bytesRead;

        try {
            bytesRead = socketChannel.read(mReadBuffer);
            if (HtspConstants.DEBUG)
                Log.v(TAG, "Read " + bytesRead + " bytes.");
        } catch (IOException e) {
            Log.e(TAG, "Failed to read from SocketChannel", e);
            return false;
        }

        if (bytesRead == -1) {
            Log.e(TAG, "Failed to read from SocketChannel, read -1 bytes");
            return false;
        } else if (bytesRead == 0) {
            // No data read, continue
            return true;
        }

        int bytesToBeConsumed = bufferStartPosition + bytesRead;
        // Flip the buffer, limit=position, position=0
        mReadBuffer.flip();

        // Read messages out of the buffer one by one, until we either:
        // * Consume 0 bytes, meaning we only have a partial message in the buffer.
        // * Have no remaining bytes left to consume.
        int bytesConsumed = -1;

        while (bytesConsumed != 0 && bytesToBeConsumed > 0) {
            // Ensure the buffer is at the start each of iteration, as we'll always have the
            // start of a message at this point (or it'll be empty)
            mReadBuffer.position(0);

            // Build a message
            HtspMessage message = mMessageSerializer.read(mReadBuffer);

            if (message == null) {
                // We didn't have enough data to read a message.
                bytesConsumed = 0;
                continue;
            }

            // Dispatch the Message to it's listeners
            mMessageDispatcher.onMessage(message);

            // We've read a full message. Our position() is set to the end of the message, and
            // out limit may also set to the position() / end of the message.

            // Figure out how much data we consumed
            bytesConsumed = mReadBuffer.position();

            // Reset the limit to the known full amount of data we had
            mReadBuffer.limit(bytesToBeConsumed);

            // Compact the buffer - position=limit, limit=capacity
            mReadBuffer.compact();

            // Figure out how much data is left to consume
            bytesToBeConsumed = bytesToBeConsumed - bytesConsumed;

            // Reset the limit to the known full amount of data we have remaining
            mReadBuffer.limit(bytesToBeConsumed);
        }

        // Place ourselves back at the right spot in the buffer, so that new reads append
        // rather than override the as yet unconsumed data.
        mReadBuffer.position(bytesToBeConsumed);

        // Ensure we have space to add data for the next iteration
        mReadBuffer.limit(mReadBuffer.capacity());

        return true;
    }

    // HtspConnection.Writer Methods
    @Override
    public boolean hasPendingData() {
        return mMessageDispatcher.hasPendingMessages();
    }

    /**
     * @param socketChannel The SocketChannel to write to
     * @return true on success, false on error
     */
    @Override
    public boolean write(@NonNull SocketChannel socketChannel) {
        // Clear the buffer out, ready for a new message.
        mWriteBuffer.clear();

        HtspMessage message = mMessageDispatcher.getMessage();

        // Write the message to the buffer
        mMessageSerializer.write(mWriteBuffer, message);

        // Flip the buffer, limit=position, position=0.
        mWriteBuffer.flip();

        try {
            int bytesWritten = socketChannel.write(mWriteBuffer);
            if (HtspConstants.DEBUG)
                Log.v(TAG, "Wrote " + bytesWritten + " bytes to SocketChannel");
        } catch (IOException e) {
            Log.e(TAG, "Failed to write buffer to SocketChannel", e);
            return false;
        }

        return true;
    }
}
