/*
 * Copyright (c) 2011 2linessoftware.com
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.twolinessoftware.android;

import com.twolinessoftware.android.framework.util.Logger;

import java.util.LinkedList;

public class SendLocationWorkerQueue {
    private static final String TAG = "SendLocationWorkerQueue";

    private LinkedList<SendLocationWorker> queue;
    private boolean running;
    private boolean stopped;
    private WorkerThread thread;
    private long delayTimeOnReplay = 0;

    private final Object lock = new Object();

    public SendLocationWorkerQueue() {
        queue = new LinkedList<SendLocationWorker>();
        running = false;
        stopped = false;
    }


    public void addToQueue(SendLocationWorker worker) {
        if (worker == null) throw new NullPointerException();
        synchronized (queue) {
            queue.addLast(worker);
        }
    }

    public synchronized void start() {
        if (running) throw new IllegalStateException("Worker queue is already running.");
        Logger.i(TAG, "Starting worker queue... " + queue.size() + " items to play");
        running = true;
        stopped = false;
        thread = new WorkerThread(delayTimeOnReplay);
        thread.start();
    }

    public synchronized void stop() {
        if (thread == null) {
            // no thread to stop...
            return;
        }
        synchronized (lock) {
            stopped = false;
            running = false;
            lock.notify();
            while (!stopped && thread.isAlive()) {
                try {
                    Logger.i(TAG, "Waiting for thread to stop.");
                    lock.wait(200);
                } catch (InterruptedException e) {
                    Logger.e(TAG, "Failed to wait until thread is stopped");
                }
            }
        }
    }

    public void reset() {
        stop();
        stopThread();
        queue = new LinkedList<SendLocationWorker>();
    }

    public void stopThread() {
        if (thread != null) {
            try {
                thread.interrupt();
            } catch (Exception e) {
                Logger.i(TAG,"SendLocationWorkerQueue.stopThread() - exception " + e.getMessage());
            }
            this.thread = null;
        }
    }

    public synchronized void setDelayTime(long delayTimeOnReplay) {
        if (running) throw new IllegalStateException("Worker queue is already running.");
        this.delayTimeOnReplay = delayTimeOnReplay;
    }

    private class WorkerThread extends Thread {

        private long timeBetweenSends; // milliseconds

        WorkerThread(long delayTimeOnReplay) {
            if (delayTimeOnReplay <= 0) throw new IllegalArgumentException("Delay cannot be zero");
            timeBetweenSends = delayTimeOnReplay;
        }

        public void run() {
            while (running && queue.size() > 0) {
                SendLocationWorker worker = queue.pop();

                synchronized (lock) {
                    try {
                        lock.wait(timeBetweenSends);
                        Logger.i(TAG,"SendLocationWorkerQueue.running - TIME_BETWEEN_SENDS : " + timeBetweenSends + " - sent at time : " + System.currentTimeMillis());
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                // Executing each worker in the current thread. Multiple threads NOT created.
                worker.run();
            }
            synchronized (lock) {
                stopped = true;
                running = false;
                lock.notify();
            }
        }
    }

}
