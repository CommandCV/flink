/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.api.common.operators;

import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.util.FlinkRuntimeException;
import org.apache.flink.util.function.FutureTaskWithException;
import org.apache.flink.util.function.RunnableWithException;
import org.apache.flink.util.function.ThrowingRunnable;

import javax.annotation.Nonnull;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/**
 * {@link java.util.concurrent.Executor} like interface for a build around a mailbox-based execution
 * model. {@code MailboxExecutor} can also execute downstream messages of a mailbox by yielding
 * control from the task thread.
 *
 * <p>All submission functions can be called from any thread and will enqueue the action for further
 * processing in a FIFO fashion.
 *
 * <p>The yielding functions avoid the following situation: One operator cannot fully process an
 * input record and blocks the task thread until some resources are available. However, since the
 * introduction of the mailbox model blocking the task thread will not only block new inputs but
 * also all events from being processed. If the resources depend on downstream operators being able
 * to process such events (e.g., timers), then we may easily arrive at some livelocks.
 *
 * <p>The yielding functions will only process events from the operator itself and any downstream
 * operator. Events of upstream operators are only processed when the input has been fully processed
 * or if they yield themselves. This method avoid congestion and potential deadlocks, but will
 * process mails slightly out-of-order, effectively creating a view on the mailbox that contains no
 * message from upstream operators.
 *
 * <p><b>All yielding functions must be called in the mailbox thread</b> to not violate the
 * single-threaded execution model. There are two typical cases, both waiting until the resource is
 * available. The main difference is if the resource becomes available through a mailbox message
 * itself or not.
 *
 * <p>If the resource becomes available through a mailbox mail, we can effectively block the task
 * thread. Implicitly, this requires the mail to be enqueued by a different thread.
 *
 * <pre>{@code
 * while (resource not available) {
 *     mailboxExecutor.yield();
 * }
 * }</pre>
 *
 * <pre>in some other thread{@code
 * mailboxExecutor.execute(() -> free resource, "freeing resource");
 * }</pre>
 *
 * <p>If the resource becomes available through an external mechanism or the corresponding mail
 * needs to be enqueued in the task thread, we cannot block.
 *
 * <pre>{@code
 * while (resource not available) {
 *     if (!mailboxExecutor.tryYield()) {
 *         // do stuff or sleep for a small amount of time
 *         if (special condition) {
 *             free resource
 *         }
 *     }
 * }
 * }</pre>
 */
@PublicEvolving
public interface MailboxExecutor {
    /** A constant for empty args to save on object allocation. */
    Object[] EMPTY_ARGS = new Object[0];

    /** Extra options to configure enqueued mails. */
    @PublicEvolving
    interface MailOptions {

        /**
         * Runtime can decide to defer execution of deferrable mails. For example, to unblock
         * subtask thread as quickly as possible, deferrable mails are not executed during {@link
         * #yield()} or {@link #tryYield()}. This is done to speed up checkpointing, by skipping
         * execution of potentially long-running mails.
         */
        boolean isDeferrable();

        /**
         * The urgent mail will be executed first compared to other mails. For example, handling
         * unaligned checkpoint barrier or some control mails are expected to be executed as soon as
         * possible.
         */
        boolean isUrgent();

        static MailOptions options() {
            return MailOptionsImpl.DEFAULT;
        }

        /** Mark this mail as deferrable. */
        static MailOptions deferrable() {
            return MailOptionsImpl.DEFERRABLE;
        }

        /** Mark this mail as urgent. */
        static MailOptions urgent() {
            return MailOptionsImpl.URGENT;
        }
    }

    /**
     * Executes the given command at some time in the future in the mailbox thread.
     *
     * <p>An optional description can (and should) be added to ease debugging and error-reporting.
     * The description may contain placeholder that refer to the provided description arguments
     * using {@link java.util.Formatter} syntax. The actual description is only formatted on demand.
     *
     * @param command the runnable task to add to the mailbox for execution.
     * @param description the optional description for the command that is used for debugging and
     *     error-reporting.
     * @throws RejectedExecutionException if this task cannot be accepted for execution, e.g.
     *     because the mailbox is quiesced or closed.
     */
    default void execute(ThrowingRunnable<? extends Exception> command, String description) {
        execute(command, description, EMPTY_ARGS);
    }

    /**
     * Executes the given command at some time in the future in the mailbox thread.
     *
     * <p>An optional description can (and should) be added to ease debugging and error-reporting.
     * The description may contain placeholder that refer to the provided description arguments
     * using {@link java.util.Formatter} syntax. The actual description is only formatted on demand.
     *
     * @param mailOptions additional options to configure behaviour of the {@code command}
     * @param command the runnable task to add to the mailbox for execution.
     * @param description the optional description for the command that is used for debugging and
     *     error-reporting.
     * @throws RejectedExecutionException if this task cannot be accepted for execution, e.g.
     *     because the mailbox is quiesced or closed.
     */
    default void execute(
            MailOptions mailOptions,
            ThrowingRunnable<? extends Exception> command,
            String description) {
        execute(mailOptions, command, description, EMPTY_ARGS);
    }

    /**
     * Executes the given command at some time in the future in the mailbox thread.
     *
     * <p>An optional description can (and should) be added to ease debugging and error-reporting.
     * The description may contain placeholder that refer to the provided description arguments
     * using {@link java.util.Formatter} syntax. The actual description is only formatted on demand.
     *
     * @param command the runnable task to add to the mailbox for execution.
     * @param descriptionFormat the optional description for the command that is used for debugging
     *     and error-reporting.
     * @param descriptionArgs the parameters used to format the final description string.
     * @throws RejectedExecutionException if this task cannot be accepted for execution, e.g.
     *     because the mailbox is quiesced or closed.
     */
    default void execute(
            ThrowingRunnable<? extends Exception> command,
            String descriptionFormat,
            Object... descriptionArgs) {
        execute(MailOptions.options(), command, descriptionFormat, descriptionArgs);
    }

    /**
     * Executes the given command at some time in the future in the mailbox thread.
     *
     * <p>An optional description can (and should) be added to ease debugging and error-reporting.
     * The description may contain placeholder that refer to the provided description arguments
     * using {@link java.util.Formatter} syntax. The actual description is only formatted on demand.
     *
     * @param mailOptions additional options to configure behaviour of the {@code command}
     * @param command the runnable task to add to the mailbox for execution.
     * @param descriptionFormat the optional description for the command that is used for debugging
     *     and error-reporting.
     * @param descriptionArgs the parameters used to format the final description string.
     * @throws RejectedExecutionException if this task cannot be accepted for execution, e.g.
     *     because the mailbox is quiesced or closed.
     */
    void execute(
            MailOptions mailOptions,
            ThrowingRunnable<? extends Exception> command,
            String descriptionFormat,
            Object... descriptionArgs);

    /**
     * Submits the given command for execution in the future in the mailbox thread and returns a
     * Future representing that command. The Future's {@code get} method will return {@code null}
     * upon <em>successful</em> completion.
     *
     * <p>WARNING: Exception raised by the {@code command} will not fail the task but are stored in
     * the future. Thus, it's an anti-pattern to call {@code submit} without handling the returned
     * future and {@link #execute(ThrowingRunnable, String, Object...)} should be used instead.
     *
     * <p>An optional description can (and should) be added to ease debugging and error-reporting.
     * The description may contain placeholder that refer to the provided description arguments
     * using {@link java.util.Formatter} syntax. The actual description is only formatted on demand.
     *
     * @param command the command to submit
     * @param descriptionFormat the optional description for the command that is used for debugging
     *     and error-reporting.
     * @param descriptionArgs the parameters used to format the final description string.
     * @return a Future representing pending completion of the task
     * @throws RejectedExecutionException if this task cannot be accepted for execution, e.g.
     *     because the mailbox is quiesced or closed.
     */
    default @Nonnull Future<Void> submit(
            @Nonnull RunnableWithException command,
            String descriptionFormat,
            Object... descriptionArgs) {
        FutureTaskWithException<Void> future = new FutureTaskWithException<>(command);
        execute(future, descriptionFormat, descriptionArgs);
        return future;
    }

    /**
     * Submits the given command for execution in the future in the mailbox thread and returns a
     * Future representing that command. The Future's {@code get} method will return {@code null}
     * upon <em>successful</em> completion.
     *
     * <p>WARNING: Exception raised by the {@code command} will not fail the task but are stored in
     * the future. Thus, it's an anti-pattern to call {@code submit} without handling the returned
     * future and {@link #execute(ThrowingRunnable, String, Object...)} should be used instead.
     *
     * <p>An optional description can (and should) be added to ease debugging and error-reporting.
     * The description may contain placeholder that refer to the provided description arguments
     * using {@link java.util.Formatter} syntax. The actual description is only formatted on demand.
     *
     * @param command the command to submit
     * @param description the optional description for the command that is used for debugging and
     *     error-reporting.
     * @return a Future representing pending completion of the task
     * @throws RejectedExecutionException if this task cannot be accepted for execution, e.g.
     *     because the mailbox is quiesced or closed.
     */
    default @Nonnull Future<Void> submit(
            @Nonnull RunnableWithException command, String description) {
        FutureTaskWithException<Void> future = new FutureTaskWithException<>(command);
        execute(future, description, EMPTY_ARGS);
        return future;
    }

    /**
     * Submits the given command for execution in the future in the mailbox thread and returns a
     * Future representing that command. The Future's {@code get} method will return {@code null}
     * upon <em>successful</em> completion.
     *
     * <p>WARNING: Exception raised by the {@code command} will not fail the task but are stored in
     * the future. Thus, it's an anti-pattern to call {@code submit} without handling the returned
     * future and {@link #execute(ThrowingRunnable, String, Object...)} should be used instead.
     *
     * <p>An optional description can (and should) be added to ease debugging and error-reporting.
     * The description may contain placeholder that refer to the provided description arguments
     * using {@link java.util.Formatter} syntax. The actual description is only formatted on demand.
     *
     * @param command the command to submit
     * @param descriptionFormat the optional description for the command that is used for debugging
     *     and error-reporting.
     * @param descriptionArgs the parameters used to format the final description string.
     * @return a Future representing pending completion of the task
     * @throws RejectedExecutionException if this task cannot be accepted for execution, e.g.
     *     because the mailbox is quiesced or closed.
     */
    default @Nonnull <T> Future<T> submit(
            @Nonnull Callable<T> command, String descriptionFormat, Object... descriptionArgs) {
        FutureTaskWithException<T> future = new FutureTaskWithException<>(command);
        execute(future, descriptionFormat, descriptionArgs);
        return future;
    }

    /**
     * Submits the given command for execution in the future in the mailbox thread and returns a
     * Future representing that command. The Future's {@code get} method will return {@code null}
     * upon <em>successful</em> completion.
     *
     * <p>WARNING: Exception raised by the {@code command} will not fail the task but are stored in
     * the future. Thus, it's an anti-pattern to call {@code submit} without handling the returned
     * future and {@link #execute(ThrowingRunnable, String, Object...)} should be used instead.
     *
     * <p>An optional description can (and should) be added to ease debugging and error-reporting.
     * The description may contain placeholder that refer to the provided description arguments
     * using {@link java.util.Formatter} syntax. The actual description is only formatted on demand.
     *
     * @param command the command to submit
     * @param description the optional description for the command that is used for debugging and
     *     error-reporting.
     * @return a Future representing pending completion of the task
     * @throws RejectedExecutionException if this task cannot be accepted for execution, e.g.
     *     because the mailbox is quiesced or closed.
     */
    default @Nonnull <T> Future<T> submit(@Nonnull Callable<T> command, String description) {
        FutureTaskWithException<T> future = new FutureTaskWithException<>(command);
        execute(future, description, EMPTY_ARGS);
        return future;
    }

    /**
     * This method starts running the command at the head of the mailbox and is intended to be used
     * by the mailbox thread to yield from a currently ongoing action to another command. The method
     * blocks until another command to run is available in the mailbox and must only be called from
     * the mailbox thread. Must only be called from the mailbox thread to not violate the
     * single-threaded execution model.
     *
     * @throws InterruptedException on interruption.
     * @throws IllegalStateException if the mailbox is closed and can no longer supply runnables for
     *     yielding.
     * @throws FlinkRuntimeException if executed {@link RunnableWithException} thrown an exception.
     */
    void yield() throws InterruptedException, FlinkRuntimeException;

    /**
     * This method attempts to run the command at the head of the mailbox. This is intended to be
     * used by the mailbox thread to yield from a currently ongoing action to another command. The
     * method returns true if a command was found and executed or false if the mailbox was empty.
     * Must only be called from the mailbox thread to not violate the single-threaded execution
     * model.
     *
     * @return true on successful yielding to another command, false if there was no command to
     *     yield to.
     * @throws IllegalStateException if the mailbox is closed and can no longer supply runnables for
     *     yielding.
     * @throws RuntimeException if executed {@link RunnableWithException} thrown an exception.
     */
    boolean tryYield() throws FlinkRuntimeException;

    /**
     * Return if operator/function should interrupt a longer computation and return from the
     * currently processed elemenent/watermark, for example in order to let Flink perform a
     * checkpoint.
     *
     * @return whether operator/function should interrupt its computation.
     */
    boolean shouldInterrupt();
}
