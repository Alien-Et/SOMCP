// SPDX-License-Identifier: AGPL-3.0-only
//
// Copyright (C) 2026 bilieebiliee1-design
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
//
// Guards for the lean tool list. The load-bearing case is gateway visibility:
// lean mode is on by default, and a gateway classified EXTRA with a niche
// category can only enter the list through popularity promotion — which needs a
// prior successful call. That once made the sub-agent gateway unreachable for a
// client that had never spawned one.
//
// The sub-agent gateway itself has since been removed from this build, so the
// guards now assert the general rule (every gateway category is advertised) and
// pin the removal explicitly, so a future re-addition has to revisit the rule.
package com.soreverse.mcp.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCatalogLeanTest {
    /** Categories whose tools fan out to a whole engine and must stay visible. */
    private val gatewayCategories = setOf("lowlevel")

    @Test
    fun everyGatewayIsAdvertisedByDefault() {
        val lean = ToolCatalog.leanNames().toSet()
        val gateways = ToolCatalog.ALL.filter { it.meta.category in gatewayCategories }
        assertTrue("there must be gateways to check", gateways.isNotEmpty())
        gateways.forEach {
            assertTrue(
                "gateway ${it.meta.name} (${it.meta.category}) is hidden from the default lean list",
                it.meta.name in lean
            )
        }
    }

    @Test
    fun everyGatewaySurvivesWithoutPriorPopularity() {
        // The regression the gateway rule exists for: no popularity map, no
        // promotion slots, and the gateway is still advertised.
        val lean = ToolCatalog.registry
            .leanNames(popularity = null, promotionSlots = 0)
            .toSet()
        ToolCatalog.ALL
            .filter { it.meta.category in gatewayCategories }
            .forEach {
                assertTrue("${it.meta.name} must survive promotion being off", it.meta.name in lean)
            }
    }

    @Test
    fun theRemovedSubAgentGatewayStaysRemoved() {
        // The AI sub-agent surface (agent_api and friends) was removed from this
        // build. Pinning its absence keeps a stray re-registration from shipping
        // silently, and documents why the "agent" gateway category is gone.
        listOf("agent_api", "spawn_subagent", "dynamic_analyze_ai", "static_analyze_ai")
            .forEach { name ->
                assertFalse("$name was removed from this build", name in ToolCatalog.names)
            }
    }

    @Test
    fun nonGatewayExtrasStillNeedPromotion() {
        // The rule must not turn lean mode into "advertise everything": an EXTRA
        // tool outside a gateway category stays out until the client uses it.
        val exotic = ToolCatalog.ALL.filter {
            it.meta.cls == ToolClass.EXTRA && it.meta.category !in gatewayCategories
        }
        assertTrue("there must be non-gateway extras", exotic.isNotEmpty())
        val lean = ToolCatalog.leanNames().toSet()
        exotic.forEach {
            assertFalse("${it.meta.name} should need promotion", it.meta.name in lean)
        }
    }

    @Test
    fun popularityPromotesNonGatewayExtrasOnly() {
        val target = ToolCatalog.ALL.first {
            it.meta.cls == ToolClass.EXTRA && it.meta.category !in gatewayCategories
        }
        val lean = ToolCatalog.leanNames(popularity = mapOf(target.meta.name to 7L))
        assertTrue("a used extra should be promoted", target.meta.name in lean)
        // Promotion must not duplicate a name that is already present.
        val gateway = ToolCatalog.ALL.first { it.meta.category in gatewayCategories }.meta.name
        val count = lean.count { it == gateway }
        assertTrue("$gateway listed $count times", count == 1)
    }

    @Test
    fun fullCatalogIsUnaffectedByTheGatewayRule() {
        assertTrue("catalog shrank", ToolCatalog.names.size >= 46)
        // The full catalog is the registry itself: nothing is filtered out of it.
        assertTrue(
            "full catalog must contain every registered handler",
            ToolCatalog.names.containsAll(ToolCatalog.ALL.map { it.meta.name })
        )
    }
}
