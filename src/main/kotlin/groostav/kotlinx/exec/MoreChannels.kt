package groostav.kotlinx.exec

import groostav.kotlinx.exec.sub.stuff
import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.launch

//TODO: why isn't this part of kotlinx.coroutines already? Something they know I dont?
internal fun ReceiveChannel<Char>.lines(
        delimiters: List<String> = listOf("\r", "\n", "\r\n")
): ReceiveChannel<String> {
    val result = produce<String>(Unconfined){

        trace { "starting lines-${this@lines}" }

        val buffer = StringBuilder(80)

        val stateMachine = LineSeparatingStateMachine(delimiters)

        this@lines.consumeEach { nextChar ->

            val newState = stateMachine.translate(nextChar)

            when (newState) {
                State.NoMatch -> {
                    buffer.append(nextChar)
                }
                State.NewMatch -> {
                    val line = buffer.takeAndClear()
                    send(line)
                }
                State.ContinuedMatch -> {
                    //noop, drop the character.
                }
            }
        }
        if (!buffer.isEmpty()) {
            send(buffer.takeAndClear())
        }

        trace { "finished lines-${this@lines}" }
    }

    return object: ReceiveChannel<String> by result {
        override fun toString() = "lines-${this@lines}"
    }
}

private fun StringBuilder.takeAndClear(): String = toString().also { setLength(0) }

private class LineSeparatingStateMachine(delimiters: List<String>) {
    val delimeterMatrix: Array<CharArray> = delimiters.map { it.toCharArray() }.toTypedArray()
    var currentMatchColumn: Int = -1
    val activeRows: MutableSet<Int> = (0 until delimeterMatrix.size).toHashSet()

    var previousState: State = State.NoMatch

    //TODO: can I make this immutable and fast?
    fun translate(next: Char): State {

        // strategy:
        // array delimieters into a jaggad matrix,
        // where rows are 'delimiter strings' (eg \r\n)
        // keep an index indicating the current 'column' being checked.
        // and keep a set of "still feasible rows" (activeRows),
        // these are the indexes of rows that still match the provided character.
        // as we see new characters, increment the column index and see if
        // any of the rows at that index match the current character.

        // for example, given we have delimeters d1="\r\n" and d2="\n",
        // and we'er parsing the string "a\r\nb", we would do
        //
        // initialization:
        //   activeRows initialized to setOf(indexOf(d1), indexOf(d2)) == setOf(0, 1)
        //   currentMatchColumn = -1,
        //   currentState = NoMatch
        //
        // translate('a') =>
        //    activeRows => removes d1 because \r isnt 'a', d2 because '\n' isnt 'a' => == emptySet()
        //    newstate is NoMatch because activeRows.isEmpty()
        //    currentMatchColumn set to -1 because activeRows isEmpty
        //    activeRows reset to setOf(0, 1)
        // translate('\r') =>
        //    activeRows keeps d1, removes d2 because '\r' isnt '\n'
        //    nextState is NewMatch because activeRows.any() and previousState == NoMatch
        //    currentMatchColumn is 0
        // translate('\n') =>
        //    activeRows keeps d1 because '\n' is '\n', no changes
        //    newState is ContinuedMatch because activeRows.any() and previousState == NewMatch
        //    currentMatchColumn is 1
        // translate('b') =>
        //    activeRows removes d1 because 'b' isnt '\n'
        //    newState is NoMatch because activeRows isEmpty
        //    activeRows reset to setOf(0, 1)
        //    currentMatchColumn is reset to -1

        //update active-rows
        moveNext(next)

        if(activeRows.isEmpty() && currentMatchColumn != 0){
            //try a new match
            reset()
            moveNext(next)
        }

        //generate new state
        val nextState = when {
            activeRows.isEmpty() -> State.NoMatch
            currentMatchColumn == 0 -> State.NewMatch
            previousState == State.NoMatch -> State.NewMatch
            previousState == State.NewMatch -> State.ContinuedMatch
            previousState == State.ContinuedMatch -> State.ContinuedMatch
            else -> TODO()
        }

        if(nextState == State.NoMatch){ reset() }

        previousState = nextState

        return nextState
    }

    private fun reset() {
        currentMatchColumn = -1
        activeRows += (0 until delimeterMatrix.size) //huge performance win from immutable collections here.
        //or actually a mask here, assuming you have less than 32 delimeters, would do the trick too.
    }

    private fun moveNext(next: Char) {
        currentMatchColumn += 1
        activeRows.removeIf { activeRowIndex ->
            val row = delimeterMatrix[activeRowIndex]
            row.size == currentMatchColumn || row[currentMatchColumn] != next
        }
    }
}

enum class State { NoMatch, NewMatch, ContinuedMatch }

internal fun <T> ReceiveChannel<T>.tail(bufferSize: Int): Channel<T> {

    val buffer = object: ArrayChannel<T>(bufferSize) {
        override fun toString() = "tail$bufferSize-${this@tail}"
    }

    trace { "allocated buffer=$bufferSize for $buffer" }

    launch(Unconfined) {
        try {
            for (item in this@tail) {
                buffer.pushForward(item)
            }
        }
        finally {
            buffer.close()
        }
    }

    return buffer
}

private suspend inline fun <T> ArrayChannel<T>.pushForward(next: T){
    try {
        while (!offer(next)) {
            val bumpedElement = receiveOrNull()
            if (bumpedElement != null){
                trace { "WARN: back-pressure forced drop '$bumpedElement' from ${this@pushForward}" }
            }
        }
    }
    catch(ex: ClosedSendChannelException){
        // means we cant take anymore ever, so throw for cancellation of parent
        throw ex
    }
    catch(ex: ClosedReceiveChannelException){
        // ignore, this means we couldnt drop the element and wont be able to make space,
        // but space might still open up.
    }
}