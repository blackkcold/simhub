package com.blackkcold.simhub;

import org.junit.Test;
import static org.junit.Assert.*;

public class HubLayoutPolicyTest {
    @Test public void compactPhoneUsesBottomTabs() {
        assertFalse(HubLayoutPolicy.navigationRail(393,852,false));
        assertFalse(HubLayoutPolicy.dualPane(393,false));
    }
    @Test public void expandedFoldableUsesRailAndTwoPanes() {
        assertTrue(HubLayoutPolicy.navigationRail(900,900,false));
        assertTrue(HubLayoutPolicy.dualPane(900,false));
    }
    @Test public void verticalHingeAvoidsRailAndKeepsTwoPanes() {
        assertFalse(HubLayoutPolicy.navigationRail(780,780,true));
        assertTrue(HubLayoutPolicy.dualPane(780,true));
        assertFalse(HubLayoutPolicy.dualPane(560,true));
    }
    @Test public void resizedOrShortWindowFallsBack() {
        assertFalse(HubLayoutPolicy.navigationRail(1100,430,false));
        assertFalse(HubLayoutPolicy.tabletopPane(410,true));
        assertTrue(HubLayoutPolicy.tabletopPane(730,true));
    }
}
