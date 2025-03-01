package dotty
package tools
package vulpix

import java.io.{ File => JFile, InputStreamReader, BufferedReader, PrintStream }
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeoutException

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.mutable

/** Vulpix spawns JVM subprocesses (`numberOfSlaves`) in order to run tests
 *  without compromising the main JVM
 *
 *  These need to be orchestrated in a safe manner with a simple protocol. This
 *  interface provides just that.
 *
 *  The protocol is defined as:
 *
 *  - master sends classpath to for which to run `Test#main` and waits for
 *    `maxDuration`
 *  - slave invokes the method and waits until completion
 *  - upon completion it sends back a `RunComplete` message
 *  - the master checks if the child is still alive
 *    - child is still alive, the output was valid
 *    - child is dead, the output is the failure message
 *
 *  If this whole chain of events is not completed within `maxDuration`, the
 *  child process is destroyed and a new child is spawned.
 */
trait RunnerOrchestration {

  /** The maximum amount of active runners, which contain a child JVM */
  def numberOfSlaves: Int

  /** The maximum duration the child process is allowed to consume before
   *  getting destroyed
   */
  def maxDuration: Duration

  /** Destroy and respawn process after each test */
  def safeMode: Boolean

  /** Running a `Test` class's main method from the specified `dir` */
  def runMain(classPath: String)(implicit summaryReport: SummaryReporting): Status =
    monitor.runMain(classPath)

  private[this] val monitor = new RunnerMonitor

  /** The runner monitor object keeps track of child JVM processes by keeping
   *  them in two structures - one for free, and one for busy children.
   *
   *  When a user calls `runMain` the monitor makes takes a free JVM and blocks
   *  until the run is complete - or `maxDuration` has passed. It then performs
   *  cleanup by returning the used JVM to the free list, or respawning it if
   *  it died
   */
  private class RunnerMonitor {

    def runMain(classPath: String)(implicit summaryReport: SummaryReporting): Status =
      withRunner(_.runMain(classPath))

    private class Runner(private[this] var process: Process) {
      private[this] var childStdout: BufferedReader = _
      private[this] var childStdin: PrintStream = _

      /** Checks if `process` is still alive
       *
       *  When `process.exitValue()` is called on an active process the caught
       *  exception is thrown. As such we can know if the subprocess exited or
       *  not.
       */
      def isAlive: Boolean =
        try { process.exitValue(); false }
        catch { case _: IllegalThreadStateException => true }

      /** Destroys the underlying process and kills IO streams */
      def kill(): Unit = {
        if (process ne null) process.destroy()
        process = null
        childStdout = null
        childStdin = null
      }

      /** Did add hook to kill the child VMs? */
      private[this] val didAddCleanupCallback = new AtomicBoolean(false)

      /** Blocks less than `maxDuration` while running `Test.main` from `dir` */
      def runMain(classPath: String)(implicit summaryReport: SummaryReporting): Status = {
        if (didAddCleanupCallback.compareAndSet(false, true)) {
          // If for some reason the test runner (i.e. sbt) doesn't kill the VM, we
          // need to clean up ourselves.
          summaryReport.addCleanup(() => killAll())
        }
        assert(process ne null,
          "Runner was killed and then reused without setting a new process")

        // Makes the encapsulating RunnerMonitor spawn a new runner
        def respawn(): Unit = {
          process.destroy()
          process = createProcess
          childStdout = null
          childStdin = null
        }

        if (childStdin eq null)
          childStdin = new PrintStream(process.getOutputStream, /* autoFlush = */ true)

        // pass file to running process
        childStdin.println(classPath)

        // Create a future reading the object:
        val readOutput = Future {
          val sb = new StringBuilder

          if (childStdout eq null)
            childStdout = new BufferedReader(new InputStreamReader(process.getInputStream))

          var childOutput = childStdout.readLine()
          while (childOutput != ChildJVMMain.MessageEnd && childOutput != null) {
            sb.append(childOutput)
            sb += '\n'
            childOutput = childStdout.readLine()
          }

          if (process.isAlive && childOutput != null) Success(sb.toString)
          else Failure(sb.toString)
        }

        // Await result for `maxDuration` and then timout and destroy the
        // process:
        val status =
          try Await.result(readOutput, maxDuration)
          catch { case _: TimeoutException =>  Timeout }

        // Handle failure of the VM:
        status match {
          case _: Success if safeMode => respawn()
          case _: Success => // no need to respawn sub process
          case _: Failure => respawn()
          case Timeout => respawn()
        }
        status
      }
    }

    /** Create a process which has the classpath of the `ChildJVMMain` and the
     *  scala library.
     */
    private def createProcess: Process = {
      val sep = sys.props("file.separator")
      val cp =
        classOf[ChildJVMMain].getProtectionDomain.getCodeSource.getLocation.getFile + ":" +
        Jars.scalaLibrary
      val javaBin = sys.props("java.home") + sep + "bin" + sep + "java"
      new ProcessBuilder(javaBin, "-cp", cp, "dotty.tools.vulpix.ChildJVMMain")
        .redirectErrorStream(true)
        .redirectInput(ProcessBuilder.Redirect.PIPE)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .start()
    }

    private[this] val allRunners = List.fill(numberOfSlaves)(new Runner(createProcess))
    private[this] val freeRunners = mutable.Queue(allRunners: _*)
    private[this] val busyRunners = mutable.Set.empty[Runner]

    private def getRunner(): Runner = synchronized {
      while (freeRunners.isEmpty) wait()

      val runner = freeRunners.dequeue()
      busyRunners += runner

      notify()
      runner
    }

    private def freeRunner(runner: Runner): Unit = synchronized {
      freeRunners.enqueue(runner)
      busyRunners -= runner
      notify()
    }

    private def withRunner[T](op: Runner => T): T = {
      val runner = getRunner()
      val result = op(runner)
      freeRunner(runner)
      result
    }

    private def killAll(): Unit = allRunners.foreach(_.kill())

    // On shutdown, we need to kill all runners:
    sys.addShutdownHook(killAll())
  }
}
