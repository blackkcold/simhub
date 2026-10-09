package com.blackkcold.simhub

/** Window rules use *available app window* dp rather than a device model or physical display. */
object HubLayoutPolicy {
    @JvmStatic fun navigationRail(widthDp:Int,heightDp:Int,verticalSeparatingHinge:Boolean):Boolean =
        widthDp>=840 && heightDp>=540 && !verticalSeparatingHinge
    @JvmStatic fun dualPane(widthDp:Int,verticalSeparatingHinge:Boolean):Boolean =
        widthDp>=840 || (widthDp>=600 && verticalSeparatingHinge)
    @JvmStatic fun tabletopPane(heightDp:Int,horizontalSeparatingHinge:Boolean):Boolean =
        horizontalSeparatingHinge && heightDp>=500
}
