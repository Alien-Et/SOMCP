// SPDX-License-Identifier: AGPL-3.0-only
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.
package com.soreverse.mcp.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObfuscDetectorTest {

    private val noTarget = -1L

    /** addr, ninstr, jump, fail */
    private fun block(addr: Long, ninstr: Int, jump: Long, fail: Long): JSONObject = JSONObject()
        .put("addr", addr)
        .put("size", 4)
        .put("ninstr", ninstr)
        .put("jump", if (jump == noTarget) JSONObject.NULL else jump)
        .put("fail", if (fail == noTarget) JSONObject.NULL else fail)

    private fun cfg(vararg blocks: JSONObject): JSONObject = JSONObject()
        .put("function", "sub_test")
        .put("addr", blocks.first().getLong("addr"))
        .put("blocks", JSONArray(blocks.toList()))
        .put("edges", JSONArray())

    /**
     * A control-flow-flattened function: nearly every real block jumps back
     * into one thin dispatcher, so that dispatcher collects most of the
     * function's edges.
     */
    private fun flattenedCfg(): JSONObject {
        // 24 blocks: 20 real ones all pointing at the dispatcher, which owns
        // two successors. Half of the real blocks are thin bogus blocks, which
        // is what bogus-control-flow splitting produces.
        val blocks = ArrayList<JSONObject>()
        for (i in 0 until 20) {
            val ninstr = if (i % 2 == 0) 2 else 5
            blocks.add(block(0x100L + i * 0x10, ninstr, 0x900, noTarget))
        }
        blocks.add(block(0x900, 4, 0x200, 0xA00))
        blocks.add(block(0xA00, 4, 0x900, noTarget))
        blocks.add(block(0xA10, 4, 0x900, noTarget))
        blocks.add(block(0xA20, 2, 0x900, noTarget))
        return cfg(*blocks.toTypedArray())
    }

    /**
     * Ordinary C++ that happens to have a popular shared tail: several blocks
     * branch to one cleanup block, but it is nowhere near the graph's hub. This
     * is what libc++ template code looks like and it must stay clean.
     */
    private fun sharedTailCfg(): JSONObject {
        val blocks = ArrayList<JSONObject>()
        // 40 straight-line blocks, each falling through to the next.
        for (i in 0 until 40) {
            blocks.add(block(0x100L + i * 0x10, 4, 0x100L + (i + 1) * 0x10, noTarget))
        }
        // Four error paths converging on one cleanup block.
        for (i in 0 until 4) {
            blocks.add(block(0x400L + i * 0x10, 4, 0x900, noTarget))
        }
        blocks.add(block(0x900, 4, noTarget, noTarget))
        return cfg(*blocks.toTypedArray())
    }

    /** An ordinary diamond plus straight-line blocks: no central dispatcher. */
    private fun plainCfg() = cfg(
        block(0x10, 4, 0x20, 0x30),
        block(0x20, 4, 0x40, noTarget),
        block(0x30, 4, 0x40, noTarget),
        block(0x40, 4, 0x50, noTarget),
        block(0x50, 4, 0x60, noTarget),
        block(0x60, 4, 0x70, noTarget),
        block(0x70, 4, noTarget, noTarget),
        block(0x80, 4, 0x90, noTarget),
        block(0x90, 4, 0xA0, noTarget),
        block(0xA0, 4, noTarget, noTarget)
    )

    @Test
    fun detectsFlattenedControlFlow() {
        val r = ObfuscDetector.analyzeFunction(flattenedCfg())
        assertEquals("flattened", r.getString("verdict"))
        assertEquals("high", r.getString("confidence"))
        assertTrue("dispatcher should be found", r.getInt("dispatcherBlocks") >= 1)
        assertTrue("factor should be high", r.getDouble("flatteningFactor") >= 8.0)
    }

    @Test
    fun doesNotFlagOrdinaryControlFlow() {
        val r = ObfuscDetector.analyzeFunction(plainCfg())
        assertEquals("clean", r.getString("verdict"))
        assertEquals(0, r.getInt("dispatcherBlocks"))
    }

    /**
     * Regression guard: libc++ template code shares error tails, which a bare
     * fan-in test misreads as flattening. A cleanup block reached by a handful
     * of blocks out of forty-odd is not a dispatcher.
     */
    @Test
    fun doesNotFlagSharedCleanupTail() {
        val r = ObfuscDetector.analyzeFunction(sharedTailCfg())
        assertEquals(0, r.getInt("dispatcherBlocks"))
        assertEquals("clean", r.getString("verdict"))
    }

    @Test
    fun emptyCfgIsReportedNotCrashed() {
        val r = ObfuscDetector.analyzeFunction(JSONObject().put("function", "sub_x").put("blocks", JSONArray()))
        assertEquals("no_cfg", r.getString("verdict"))
        assertEquals(0, r.getInt("blockCount"))
    }

    @Test
    fun tinyFunctionIsNotAnalysedForDispatchers() {
        // Fewer than 6 blocks cannot host a dispatcher; must not be judged.
        val r = ObfuscDetector.analyzeFunction(
            cfg(
                block(0x10, 2, 0x20, 0x20),
                block(0x20, 2, noTarget, noTarget)
            )
        )
        assertEquals(0, r.getInt("dispatcherBlocks"))
        assertEquals("clean", r.getString("verdict"))
    }
}
