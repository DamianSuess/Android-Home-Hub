/*
 * MIT License
 *
 * Copyright (c) 2019 Adetunji Dahunsi
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.tunjid

import android.graphics.Color
import android.view.View
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.MenuRes
import androidx.annotation.StringRes


data class UiState(
        @param:DrawableRes @field:DrawableRes @get:DrawableRes val fabIcon: Int,
        @param:StringRes @field:StringRes @get:StringRes val fabText: Int,
        val fabShows: Boolean,
        val fabExtended: Boolean,
        @param:MenuRes @field:MenuRes @get:MenuRes val toolBarMenu: Int,
        val toolbarShows: Boolean,
        val toolbarInvalidated: Boolean,
        val toolbarTitle: CharSequence,
        @param:MenuRes @field:MenuRes @get:MenuRes val altToolBarMenu: Int,
        val altToolBarShows: Boolean,
        val altToolbarInvalidated: Boolean,
        val altToolbarTitle: CharSequence,
        @param:ColorInt @field:ColorInt @get:ColorInt val navBarColor: Int,
        val bottomNavShows: Boolean,
        val systemUiShows: Boolean,
        val hasLightNavBar: Boolean,
        val fabClickListener: View.OnClickListener?
) {

    fun diff(
            force: Boolean,
            newState: UiState,
            showsFabConsumer: (Boolean) -> Unit,
            showsToolbarConsumer: (Boolean) -> Unit,
            showsAltToolbarConsumer: (Boolean) -> Unit,
            showsSystemUIConsumer: (Boolean) -> Unit,
            hasLightNavBarConsumer: (Boolean) -> Unit,
            navBarColorConsumer: (Int) -> Unit,
            fabStateConsumer: (Int, Int) -> Unit,
            fabExtendedConsumer: (Boolean) -> Unit,
            toolbarStateConsumer: (Int, Boolean, CharSequence) -> Unit,
            altToolbarStateConsumer: (Int, Boolean, CharSequence) -> Unit,
            fabClickListenerConsumer: (View.OnClickListener?) -> Unit
    ) {
        onChanged(force, newState, UiState::toolBarMenu, UiState::toolbarInvalidated, UiState::toolbarTitle) {
            toolbarStateConsumer(toolBarMenu, toolbarInvalidated, toolbarTitle)
        }

        onChanged(force, newState, UiState::toolbarShows) { showsToolbarConsumer(toolbarShows) }

        onChanged(force, newState, UiState::altToolBarMenu, UiState::altToolbarInvalidated, UiState::altToolbarTitle) {
            altToolbarStateConsumer(altToolBarMenu, altToolbarInvalidated, altToolbarTitle)
        }

        onChanged(force, newState, UiState::altToolBarShows) { showsAltToolbarConsumer(altToolBarShows) }

        onChanged(force, newState, UiState::fabShows) { showsFabConsumer(fabShows) }
        onChanged(force, newState, UiState::fabExtended) { fabExtendedConsumer(fabExtended) }
        onChanged(force, newState, UiState::fabIcon, UiState::fabText) { fabStateConsumer(fabIcon, fabText) }

        onChanged(force, newState, UiState::navBarColor) { navBarColorConsumer(navBarColor) }
        onChanged(force, newState, UiState::hasLightNavBar) { hasLightNavBarConsumer(hasLightNavBar) }

        showsSystemUIConsumer(newState.systemUiShows)
        fabClickListenerConsumer.invoke(newState.fabClickListener)
    }

    private inline fun onChanged(force:Boolean, that: UiState, vararg selectors: (UiState) -> Any?, invocation: UiState.() -> Unit) {
        if (force || selectors.any { it(this) != it(that) }) invocation(that)
    }

    companion object {
        fun freshState(): UiState = UiState(
                toolBarMenu = 0,
                toolbarShows = true,
                toolbarInvalidated = false,
                toolbarTitle = "",
                altToolBarMenu = 0,
                altToolBarShows = false,
                altToolbarInvalidated = false,
                altToolbarTitle = "",
                fabIcon = 0,
                fabText = 0,
                fabShows = true,
                fabExtended = true,
                navBarColor = Color.BLACK,
                bottomNavShows = true,
                systemUiShows = true,
                hasLightNavBar = false,
                fabClickListener = null
        )
    }
}