/*
 * Copyright 2021 Marco Cipriani (@marcocipriani01)
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.marcocipriani01.telescopetouch.renderer.util;

import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.Buffer;

import javax.microedition.khronos.opengles.GL11;

/**
 * This is a utility class which encapsulates and OpenGL buffer object.  Several other classes
 * need to be able to lazily create OpenGL buffers, so this class takes care of the work of lazily
 * creating and updating them.
 *
 * @author jpowell
 */
public class GLBuffer {

    // TODO(jpowell): This is ugly, we should have a buffer factory which knows
    // this rather than a static constant.  I should refactor this accordingly
    // when I get a chance.
    private static boolean sCanUseVBO = false;
    private final int mBufferType;
    private Buffer mBuffer = null;
    private int mBufferSize = 0;
    private int mGLBufferID = -1;
    private boolean mHasLoggedStackTraceOnError = false;

    GLBuffer(int bufferType) {
        mBufferType = bufferType;
    }

    public static void setCanUseVBO(boolean canUseVBO) {
        sCanUseVBO = canUseVBO;
    }

    // A caller should verify that this returns true before using a GLBuffer.
    // If this returns false, any operation using the VBO will be a no-op.
    public static boolean canUseVBO() {
        return sCanUseVBO;
    }

    // Unset any GL buffer which is set on the device.  You need to call this if you want to render
    // without VBOs.  Otherwise it will try to use whatever buffer is currently set.
    public static void unbind(GL11 gl) {
        if (canUseVBO()) {
            gl.glBindBuffer(GL11.GL_ARRAY_BUFFER, 0);
            gl.glBindBuffer(GL11.GL_ELEMENT_ARRAY_BUFFER, 0);
        }
    }

    public void bind(GL11 gl, Buffer buffer, int bufferSize) {
        if (canUseVBO()) {
            maybeRegenerateBuffer(gl, buffer, bufferSize);
            gl.glBindBuffer(mBufferType, mGLBufferID);
        } else {
            Log.e("GLBuffer", "Trying to use a VBO, but they are unsupported");
            // Log a stack trace the first time we see this for any given buffer.
            if (!mHasLoggedStackTraceOnError) {
                StringWriter writer = new StringWriter();
                new Throwable().printStackTrace(new PrintWriter(writer));
                Log.e("SkyRenderer", writer.toString());
                mHasLoggedStackTraceOnError = true;
            }
        }
    }

    public void reload() {
        // Just reset all of the values so we'll reload on the next call
        // to maybeRegenerateBuffer.
        mBuffer = null;
        mBufferSize = 0;
        mGLBufferID = -1;
    }

    private void maybeRegenerateBuffer(GL11 gl, Buffer buffer, int bufferSize) {
        if (buffer != mBuffer || bufferSize != mBufferSize) {
            mBuffer = buffer;
            mBufferSize = bufferSize;

            // Allocate the buffer ID if we don't already have one.
            if (mGLBufferID == -1) {
                int[] buffers = new int[1];
                gl.glGenBuffers(1, buffers, 0);
                mGLBufferID = buffers[0];
            }

            gl.glBindBuffer(mBufferType, mGLBufferID);
            gl.glBufferData(mBufferType, bufferSize, buffer, GL11.GL_STATIC_DRAW);
        }
    }
}