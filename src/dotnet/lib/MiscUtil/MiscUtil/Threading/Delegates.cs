using System;

namespace MiscUtil.Threading
{
	/// <summary>
	/// Delegate for handling exceptions.
	/// </summary>
	public delegate void ExceptionHandler(object sender, Exception e);

	#region Delegates from CustomThreadPool
	/// <summary>
	/// Delegate for handling exceptions thrown by work items executing
	/// in a custom thread pool.
	/// </summary>
	/// <param name="pool">The pool which created the worker thread</param>
	/// <param name="workItem">The work item which threw the exception</param>
	/// <param name="e">The exception thrown</param>
	/// <param name="handled">
	/// Whether or not the exception has been handled by this delegate. The value
	/// of this parameter will be false on entry, and changing it to true will
	/// prevent any further delegates in the event from being executed.
	/// </param>
	public delegate void ThreadPoolExceptionHandler (CustomThreadPool pool, 
													 ThreadPoolWorkItem workItem, 
													 Exception e,
	                                                 ref bool handled);

	/// <summary>
	/// Delegate for handling the event that a thread is about to execute
	/// a work item.
	/// </summary>
	/// <param name="pool">The pool which created the worker thread</param>
	/// <param name="workItem">The work item which is about to execute</param>
	/// <param name="cancel">
	/// Whether or not the work item should be cancelled. The value
	/// of this parameter will be false on entry, and changing it to true will
	/// prevent any further delegates in the event from being executed, and
	/// prevent the work item itself from being executed.
	/// </param>
	public delegate void BeforeWorkItemHandler (CustomThreadPool pool,
	                                            ThreadPoolWorkItem workItem,
	                                            ref bool cancel);

	/// <summary>
	/// Delegate for handling the event that a thread has executed a work item.
	/// </summary>
	/// <param name="pool">The pool which created the worker thread</param>
	/// <param name="workItem">The work item which has executed</param>
	public delegate void AfterWorkItemHandler (CustomThreadPool pool,
	                                           ThreadPoolWorkItem workItem);
	#endregion

	/// <summary>
	/// Delegate for handling the event that a thread has changed state
	/// (e.g. it's about to execute a work item, it's just executed one, etc).
	/// Also used for requests for a thread to change state (e.g. if a stop
	/// request has been received).
	/// </summary>
	public delegate void ThreadProgress(object sender);

	/// <summary>
	/// Represents the method that is executed by a ThreadController.
	/// </summary>
	public delegate void ControlledThreadStart (ThreadController controller, object state);
}
