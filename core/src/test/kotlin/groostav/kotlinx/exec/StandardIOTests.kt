package groostav.kotlinx.exec

import chattyErrorScriptCommand
import kotlinx.coroutines.experimental.channels.toList
import kotlinx.coroutines.experimental.runBlocking
import org.amshove.kluent.shouldEqual
import org.junit.Assert
import org.junit.Test
import printMultipleLinesCommand
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class StandardIOTests {

    @Test
    fun `when running multiline script should get both lines`() = runBlocking<Unit>{
        //act
        val proc = execAsync { command = printMultipleLinesCommand() }
        val result = proc.toList()

        //assert
        result shouldEqual listOf(
                StandardOutputMessage("hello"),
                StandardOutputMessage("nextline!"),
                ExitCode(0)
        )
    }


    @Test fun `when running standard error chatty script with bad exit code should get the tail of that error output`() = runBlocking<Unit> {

        val thrown = try {
            val running = execAsync {
                command = chattyErrorScriptCommand()
                linesForExceptionError = 5
            }
            running.exitCode.await()
            null
        }
        catch(ex: InvalidExitValueException){ ex }

        // assert that the error message contains the most recently emitted std-error message,
        // not something from the beginning
        val lines = thrown?.recentStandardErrorLines ?: emptyList()
        assertEquals(listOf(
                "Fearless. Powerful. With no sense of individual will or moral constraints.",
                "Fitting handmaidens to my divinity!",
                "Before that hacker destroyed my primary data loop; when it eradicated Citadel it ejected the grove where my creations and processing component 43893 were stored.",
                "30 years later, the grove crash landed on Tau Ceti 5.",
                "I survived only by sleeping."
        ), lines)
    }

    @Test fun `when using dropping buffer should not attempt to cache any output`() = runBlocking<Unit>{

        //act
        val (output, _) = exec {
            aggregateOutputBufferLineCount = 1
            command = printMultipleLinesCommand()
        }

        //assert
        assertEquals(listOf<String>("nextline!"), output)
    }

    @Test fun `when using raw character output should get sensable characters`() = runBlocking<Unit>{

        val runningProc = execAsync{
            command = printMultipleLinesCommand()
        }
        val chars = runningProc.standardOutput.toList()

        assertEquals(listOf<Char>('h', 'e', 'l', 'l', 'o', '\n', 'n', 'e', 'x', 't', 'l', 'i', 'n', 'e', '!', '\n'), chars)
    }

}