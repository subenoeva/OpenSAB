package com.opensab.cluster

/**
 * A page that *does* something while it is mounted, as opposed to one that merely renders what a
 * source already holds.
 *
 * The engine gives pages no scope of their own, and [BaseStateManager] is the only thing here that
 * knows the instant a page stops being mounted. For a microphone, missing that instant means an open
 * recorder nobody is listening to, which is why the lifetime belongs to the engine and not to the
 * page.
 *
 * A page that has nothing to do does not declare this. Declaring it and returning immediately is
 * the same as not declaring it, only harder to read.
 */
internal interface WorkingPage : PageProvider {

    /**
     * Runs while the page is mounted, and is cancelled the instant it stops being.
     *
     * It is started once per **mount** and never per frame: a redraw is not a remount, so
     * invalidating the page does not start a second listen. What it returns is the navigation the
     * page asks for when its work is over — a dictation that has been sent pops itself.
     *
     * > **The action is applied from another job, never from this one.**
     * > Applying it re-enters the projection, which cancels this very job. Returned into the
     * > coroutine that produced it, the cancellation would land mid-flight: the page would leave
     * > the stack and the frame underneath would never be written, leaving the cluster showing a
     * > page that is gone. [BaseStateManager] therefore hands it over a channel collected by a
     * > session-scoped job. It is invisible in the code, which is why it is written here.
     *
     * Nothing wraps this call in a `runCatching`: cancelling is a normal end and the
     * `CancellationException` has to reach the job.
     */
    suspend fun work(): NavigationAction
}
