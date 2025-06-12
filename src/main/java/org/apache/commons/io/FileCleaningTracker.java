/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.io;

import java.io.File;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Keeps track of files awaiting deletion, and deletes them when an associated
 * marker object is reclaimed by the garbage collector.
 * <p>
 * This utility creates a background thread to handle file deletion.
 * Each file to be deleted is registered with a handler object.
 * When the handler object is garbage collected, the file is deleted.
 * </p>
 * <p>
 * In an environment with multiple class loaders (a servlet container, for
 * example), you should consider stopping the background thread if it is no
 * longer needed. This is done by invoking the method
 * {@link #exitWhenFinished}, typically in
 * {@code javax.servlet.ServletContextListener.contextDestroyed(javax.servlet.ServletContextEvent)} or similar.
 * </p>
 */
public class FileCleaningTracker {

    // Note: fields are package protected to allow use by test cases

    /**
     * Reaper 翻译过来是“收割器”。这里直接继承 Thread，也行吧，不过应该可以优化为实现 Runnable。
     */
    private final class Reaper extends Thread {
        /**
         * Constructs a new Reaper
         */
        Reaper() {
            // 设置线程名
            super("File Reaper");
            // 设置线程优先级，最大
            setPriority(MAX_PRIORITY);
            // 守护进程，不阻止程序结束
            setDaemon(true);
        }

        /**
         * Runs the reaper thread that will delete files as their associated
         * marker objects are reclaimed by the garbage collector.
         */
        @Override
        public void run() {
            // thread exits when exitWhenFinished is true and there are no more tracked objects
            // 终结条件就是：调用了 exit，且 trackers 空
            // 这样考虑到了尽管 exit 了，但是依然有任务需要执行，那么还会继续监控直到结束
            // 同时，exit 之后不再接受新的任务
            // 🤔 这里用 trackers.isEmpty()
            while (!exitWhenFinished || !trackers.isEmpty()) {
                try {
                    // Wait for a tracker to remove.
                    // ReferenceQueue 引用队列 remove(内部有阻塞效果，且无限超时)
                    // 阻塞是通过 synchronized 监视器锁 Object.wait(0) 完成的
                    final Tracker tracker = (Tracker) q.remove(); // cannot return null

                    // 若 ReferenceQueue 有元素，说明 referent 被 GC 了，这时候从队列中移除，同时也删除 Tracker
                    trackers.remove(tracker);
                    if (!tracker.delete()) {
                        deleteFailures.add(tracker.getPath());
                    }
                    tracker.clear();
                } catch (final InterruptedException e) {
                    // 这里选择继续，当调用 exit 时，会 interrupt 该线程，不过可能最多也就是将 ReferenceQueue 从 WAIT 状态变成 RUNNABLE
                    // 然后继续循环
                    continue;
                }
            }
        }
    }

    /**
     * Inner class which acts as the reference for a file pending deletion.
     */
    private static final class Tracker extends PhantomReference<Object> {

        /**
         * The full path to the file being tracked.
         * 正在跟踪的文件的全路径
         */
        private final String path;

        /**
         * The strategy for deleting files.
         * 删除文件的策略。就是普通删除，还是强制删除
         */
        private final FileDeleteStrategy deleteStrategy;

        /**
         * Constructs an instance of this class from the supplied parameters.
         *
         * @param path           the full path to the file to be tracked, not null
         * @param deleteStrategy the strategy to delete the file, null means normal
         * @param marker         the marker object used to track the file, not null
         * @param queue          the queue on to which the tracker will be pushed, not null
         */
        Tracker(final String path, final FileDeleteStrategy deleteStrategy, final Object marker,
                final ReferenceQueue<? super Object> queue) {
            // super 这里就是父类 PhantomReference
            // 传入两个对象，一个是虚引用对象，还有个是引用队列
            super(marker, queue);
            this.path = path;
            this.deleteStrategy = deleteStrategy == null ? FileDeleteStrategy.NORMAL : deleteStrategy;
        }

        /**
         * Deletes the file associated with this tracker instance.
         *
         * @return {@code true} if the file was deleted successfully;
         * {@code false} otherwise.
         */
        public boolean delete() {
            // 如果对象被回收，就会放到引用队列中，然后被线程删除
            return deleteStrategy.deleteQuietly(new File(path));
        }

        /**
         * Gets the path.
         *
         * @return the path
         */
        public String getPath() {
            return path;
        }
    }

    /**
     * Queue of {@link Tracker} instances being watched.
     */
    ReferenceQueue<Object> q = new ReferenceQueue<>();

    /**
     * Collection of {@link Tracker} instances in existence.
     */
    final Collection<Tracker> trackers = Collections.synchronizedSet(new HashSet<>()); // synchronized

    /**
     * Collection of File paths that failed to delete.
     */
    final List<String> deleteFailures = Collections.synchronizedList(new ArrayList<>());

    /**
     * Whether to terminate the thread when the tracking is complete.
     */
    volatile boolean exitWhenFinished;

    /**
     * The thread that will clean up registered files.
     */
    Thread reaper;

    /**
     * Construct a new instance.
     */
    public FileCleaningTracker() {
        // empty
    }

    /**
     * Adds a tracker to the list of trackers.
     *
     * @param path           the full path to the file to be tracked, not null
     * @param marker         the marker object used to track the file, not null
     * @param deleteStrategy the strategy to delete the file, null means normal
     */
    private synchronized void addTracker(final String path, final Object marker, final FileDeleteStrategy
            deleteStrategy) {
        // synchronized block protects reaper
        // 停止之后不再接受新任务
        if (exitWhenFinished) {
            throw new IllegalStateException("No new trackers can be added once exitWhenFinished() is called");
        }
        // 惰性启动线程
        if (reaper == null) {
            reaper = new Reaper();
            reaper.start();
        }

        // 注意：第 4 个参数 ReferenceQueue 是当前对象的属性
        trackers.add(new Tracker(path, deleteStrategy, marker, q));
    }

    /**
     * Call this method to cause the file cleaner thread to terminate when
     * there are no more objects being tracked for deletion.
     * 调用此方法可以是的文件清理线程在没有更多对象需要跟踪删除时终止。
     * <p>
     * 一般，你不需要调用这个方法，因为 JVM 退出时，文件清理线程会自动退出。
     * 什么时候需要这个方法，一般是 Servlet Container 中，不同的 Web 由不同的类加载器加载，
     * 即使 Web 关闭，类加载器也终止了，但是 Servlet Container 没有停止，这个线程可能依然继续存在。
     * In a simple environment, you don't need this method as the file cleaner
     * thread will simply exit when the JVM exits. In a more complex environment,
     * with multiple class loaders (such as an application server), you should be
     * aware that the file cleaner thread will continue running even if the class
     * loader it was started from terminates. This can constitute a memory leak.
     * <p>
     * For example, suppose that you have developed a web application, which
     * contains the commons-io jar file in your WEB-INF/lib directory. In other
     * words, the FileCleaner class is loaded through the class loader of your
     * web application. If the web application is terminated, but the servlet
     * container is still running, then the file cleaner thread will still exist,
     * posing a memory leak.
     * <p>
     * This method allows the thread to be terminated. Simply call this method
     * in the resource cleanup code, such as
     * {@code javax.servlet.ServletContextListener.contextDestroyed(javax.servlet.ServletContextEvent)}.
     * Once called, no new objects can be tracked by the file cleaner.
     */
    public synchronized void exitWhenFinished() {
        // synchronized block protects reaper
        exitWhenFinished = true;
        if (reaper != null) {
            synchronized (reaper) {
                reaper.interrupt();
            }
        }
    }

    /**
     * Gets a copy of the file paths that failed to delete.
     *
     * @return a copy of the file paths that failed to delete
     * @since 2.0
     */
    public List<String> getDeleteFailures() {
        return new ArrayList<>(deleteFailures);
    }

    /**
     * Gets the number of files currently being tracked, and therefore
     * awaiting deletion.
     *
     * @return the number of files being tracked
     */
    public int getTrackCount() {
        return trackers.size();
    }

    /**
     * Tracks the specified file, using the provided marker, deleting the file
     * when the marker instance is garbage collected.
     * The {@link FileDeleteStrategy#NORMAL normal} deletion strategy will be used.
     *
     * @param file   the file to be tracked, not null
     * @param marker the marker object used to track the file, not null
     * @throws NullPointerException if the file is null
     */
    public void track(final File file, final Object marker) {
        track(file, marker, null);
    }

    /**
     * Tracks the specified file, using the provided marker, deleting the file
     * when the marker instance is garbage collected.
     * The specified deletion strategy is used.
     *
     * @param file           the file to be tracked, not null
     * @param marker         the marker object used to track the file, not null
     * @param deleteStrategy the strategy to delete the file, null means normal
     * @throws NullPointerException if the file is null
     */
    public void track(final File file, final Object marker, final FileDeleteStrategy deleteStrategy) {
        Objects.requireNonNull(file, "file");
        addTracker(file.getPath(), marker, deleteStrategy);
    }

    /**
     * Tracks the specified file, using the provided marker, deleting the file
     * when the marker instance is garbage collected.
     * The {@link FileDeleteStrategy#NORMAL normal} deletion strategy will be used.
     *
     * @param file   the file to be tracked, not null
     * @param marker the marker object used to track the file, not null
     * @throws NullPointerException if the file is null
     * @since 2.14.0
     */
    public void track(final Path file, final Object marker) {
        track(file, marker, null);
    }

    /**
     * Tracks the specified file, using the provided marker, deleting the file
     * when the marker instance is garbage collected.
     * The specified deletion strategy is used.
     *
     * @param file           the file to be tracked, not null
     * @param marker         the marker object used to track the file, not null
     * @param deleteStrategy the strategy to delete the file, null means normal
     * @throws NullPointerException if the file is null
     * @since 2.14.0
     */
    public void track(final Path file, final Object marker, final FileDeleteStrategy deleteStrategy) {
        Objects.requireNonNull(file, "file");
        addTracker(file.toAbsolutePath().toString(), marker, deleteStrategy);
    }

    /**
     * Tracks the specified file, using the provided marker, deleting the file
     * when the marker instance is garbage collected.
     * The {@link FileDeleteStrategy#NORMAL normal} deletion strategy will be used.
     *
     * @param path   the full path to the file to be tracked, not null
     * @param marker the marker object used to track the file, not null
     * @throws NullPointerException if the path is null
     */
    public void track(final String path, final Object marker) {
        track(path, marker, null);
    }

    /**
     * Tracks the specified file, using the provided marker, deleting the file
     * when the marker instance is garbage collected.
     * The specified deletion strategy is used.
     *
     * @param path           the full path to the file to be tracked, not null
     * @param marker         the marker object used to track the file, not null
     * @param deleteStrategy the strategy to delete the file, null means normal
     * @throws NullPointerException if the path is null
     */
    public void track(final String path, final Object marker, final FileDeleteStrategy deleteStrategy) {
        Objects.requireNonNull(path, "path");
        addTracker(path, marker, deleteStrategy);
    }

}
