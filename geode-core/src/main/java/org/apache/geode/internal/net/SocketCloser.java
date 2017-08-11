/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.internal.net;

import org.apache.geode.SystemFailure;
import org.apache.geode.internal.logging.LogService;
import org.apache.geode.internal.logging.LoggingThreadGroup;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * This class allows sockets to be closed without blocking. In some cases we have seen a call of
 * socket.close block for minutes. This class maintains a thread pool for every other member we have
 * connected sockets to. Any request to close by default returns immediately to the caller while the
 * close is called by a background thread. The requester can wait for a configured amount of time by
 * setting the "p2p.ASYNC_CLOSE_WAIT_MILLISECONDS" system property. Idle threads that are not doing
 * a close will timeout after 2 minutes. This can be configured by setting the
 * "p2p.ASYNC_CLOSE_POOL_KEEP_ALIVE_SECONDS" system property. A pool exists for each remote address
 * that we have a socket connected to. That way if close is taking a long time to one address we can
 * still get closes done to another address. Each address pool by default has at most 8 threads.
 * This max threads can be configured using the "p2p.ASYNC_CLOSE_POOL_MAX_THREADS" system property.
 */
public class SocketCloser {
  private static final Logger logger = LogService.getLogger();
  /**
   * Number of seconds to wait before timing out an unused async close thread. Default is 120 (2
   * minutes).
   */
  static final long ASYNC_CLOSE_POOL_KEEP_ALIVE_SECONDS =
      Long.getLong("p2p.ASYNC_CLOSE_POOL_KEEP_ALIVE_SECONDS", 120);
  /**
   * Maximum number of threads that can be doing a socket close. Any close requests over this max
   * will queue up waiting for a thread.
   */
  private static final int ASYNC_CLOSE_POOL_MAX_THREADS =
      Integer.getInteger("p2p.ASYNC_CLOSE_POOL_MAX_THREADS", 8);
  /**
   * How many milliseconds the synchronous requester waits for the async close to happen. Default is
   * 0. Prior releases waited 50ms.
   */
  private static final long ASYNC_CLOSE_WAIT_MILLISECONDS =
      Long.getLong("p2p.ASYNC_CLOSE_WAIT_MILLISECONDS", 0);


  private final long asyncClosePoolKeepAliveSeconds;
  private final int asyncClosePoolMaxThreads;
  private final long asyncCloseWaitTime;
  private final TimeUnit asyncCloseWaitUnits;
  private boolean closed;
  private final ExecutorService socketCloserThreadPool;

  public SocketCloser() {
    this(ASYNC_CLOSE_POOL_KEEP_ALIVE_SECONDS, ASYNC_CLOSE_POOL_MAX_THREADS,
        ASYNC_CLOSE_WAIT_MILLISECONDS, TimeUnit.MILLISECONDS);
  }

  public SocketCloser(int asyncClosePoolMaxThreads, long asyncCloseWaitMillis) {
    this(ASYNC_CLOSE_POOL_KEEP_ALIVE_SECONDS, asyncClosePoolMaxThreads, asyncCloseWaitMillis,
        TimeUnit.MILLISECONDS);
  }

  public SocketCloser(long asyncClosePoolKeepAliveSeconds, int asyncClosePoolMaxThreads,
      long asyncCloseWaitTime, TimeUnit asyncCloseWaitUnits) {
    this.asyncClosePoolKeepAliveSeconds = asyncClosePoolKeepAliveSeconds;
    this.asyncClosePoolMaxThreads = asyncClosePoolMaxThreads;
    this.asyncCloseWaitTime = asyncCloseWaitTime;
    this.asyncCloseWaitUnits = asyncCloseWaitUnits;

    final ThreadGroup threadGroup =
        LoggingThreadGroup.createThreadGroup("Socket asyncClose", logger);
    ThreadFactory threadFactory = command -> {
      Thread thread = new Thread(threadGroup, command);
      thread.setDaemon(true);
      return thread;
    };
    socketCloserThreadPool = new ThreadPoolExecutor(this.asyncClosePoolMaxThreads,
        this.asyncClosePoolMaxThreads, this.asyncClosePoolKeepAliveSeconds, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(), threadFactory);
  }

  public int getMaxThreads() {
    return this.asyncClosePoolMaxThreads;
  }

  private boolean isClosed() {
    return this.closed;
  }

  /**
   * Call close when you are all done with your socket closer. If you call asyncClose after close is
   * called then the asyncClose will be done synchronously.
   */
  public void close() {
    if (!this.closed) {
      this.closed = true;
      socketCloserThreadPool.shutdown();
    }
  }

  /**
   * Closes the specified socket in a background thread. In some cases we see close hang (see bug
   * 33665). Depending on how the SocketCloser is configured (see ASYNC_CLOSE_WAIT_MILLISECONDS)
   * this method may block for a certain amount of time. If it is called after the SocketCloser is
   * closed then a normal synchronous close is done.
   * 
   * @param socket the socket to close
   * @param runnableCode an optional Runnable with stuff to execute in the async thread
   */
  public void asyncClose(final Socket socket, final Runnable runnableCode) {
    if (socket == null || socket.isClosed()) {
      return;
    }

    boolean doItInline = false;
    try {
      if (isClosed()) {
        // this SocketCloser has been closed so do a synchronous, inline, close
        doItInline = true;
      } else {
        socketCloserThreadPool.execute(() -> {
          if (runnableCode != null) {
            runnableCode.run();
          }
          inlineClose(socket);
        });
        if (this.asyncCloseWaitTime != 0) {
          try {
            Future future = socketCloserThreadPool.submit(() -> {
              if (runnableCode != null) {
                runnableCode.run();
              }
              inlineClose(socket);
            });
            future.get(this.asyncCloseWaitTime, this.asyncCloseWaitUnits);
          } catch (InterruptedException | ExecutionException | TimeoutException e) {
            // We want this code to wait at most 50ms for the close to happen.
            // It is ok to ignore these exception and let the close continue
            // in the background.
          }
        }
      }
    } catch (OutOfMemoryError ignore) {
      // If we can't start a thread to close the socket just do it inline.
      // See bug 50573.
      doItInline = true;
    }
    if (doItInline) {
      if (runnableCode != null) {
        runnableCode.run();
      }
      inlineClose(socket);
    }
  }


  /**
   * Closes the specified socket
   * 
   * @param socket the socket to close
   */
  private void inlineClose(final Socket socket) {
    // the next two statements are a mad attempt to fix bug
    // 36041 - segv in jrockit in pthread signaling code. This
    // seems to alleviate the problem.
    try {
      socket.shutdownInput();
      socket.shutdownOutput();
    } catch (Exception e) {
    }
    try {
      socket.close();
    } catch (IOException ignore) {
    } catch (VirtualMachineError err) {
      SystemFailure.initiateFailure(err);
      // If this ever returns, rethrow the error. We're poisoned
      // now, so don't let this thread continue.
      throw err;
    } catch (java.security.ProviderException pe) {
      // some ssl implementations have trouble with termination and throw
      // this exception. See bug #40783
    } catch (Error e) {
      // Whenever you catch Error or Throwable, you must also
      // catch VirtualMachineError (see above). However, there is
      // _still_ a possibility that you are dealing with a cascading
      // error condition, so you also need to check to see if the JVM
      // is still usable:
      SystemFailure.checkFailure();
      // Sun's NIO implementation has been known to throw Errors
      // that are caused by IOExceptions. If this is the case, it's
      // okay.
      if (e.getCause() instanceof IOException) {
        // okay...
      } else {
        throw e;
      }
    }
  }
}
