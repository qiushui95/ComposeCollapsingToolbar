/*
 * Copyright (c) 2021 onebone <me@onebone.me>
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
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */

package me.onebone.toolbar

import android.annotation.SuppressLint
import androidx.annotation.FloatRange
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.max

@Stable
class CollapsingToolbarScaffoldState(
	val toolbarState: CollapsingToolbarState,
	initialOffsetY: Int = 0
) {
	val offsetY: Int
		get() = offsetYState.value

	internal val offsetYState = mutableStateOf(initialOffsetY)

	internal var scrollStrategy: ScrollStrategy? = null

	val offsetProgress: Float
		@FloatRange(from = 0.0, to = 1.0)
		get() = scrollStrategy?.let { validScrollStrategy ->
			when (validScrollStrategy) {
				ScrollStrategy.EnterAlways,
				ScrollStrategy.EnterAlwaysCollapsed -> {
					1f - (-offsetY.toFloat() / toolbarState.minHeight.toFloat()).coerceIn(0f, 1f)
				}

				ScrollStrategy.ExitUntilCollapsed -> 1f
			}
		} ?: 0f

	val totalProgress: Float
		@FloatRange(from = 0.0, to = 1.0)
		get() {
			val toolbarMaxHeight = toolbarState.maxHeight.toFloat()

			return ((offsetY + toolbarState.height) / toolbarMaxHeight).coerceIn(0f, 1f)
		}

	@ExperimentalToolbarApi
	suspend fun offsetExpand(duration: Int = SPRING_BASED_DURATION) {
		scrollStrategy?.let { validScrollStrategy ->
			val anim = AnimationState(offsetY.toFloat())

			anim.animateTo(
				when (validScrollStrategy) {
					ScrollStrategy.EnterAlways,
					ScrollStrategy.EnterAlwaysCollapsed -> 0f

					ScrollStrategy.ExitUntilCollapsed -> return
				},
				if (duration == SPRING_BASED_DURATION) {
					spring()
				} else {
					tween(duration)
				}
			) {
				offsetYState.value = value.toInt()
			}
		}
	}

	@ExperimentalToolbarApi
	suspend fun offsetCollapse(duration: Int = SPRING_BASED_DURATION) {
		scrollStrategy?.let { validScrollStrategy ->
			val anim = AnimationState(offsetY.toFloat())

			anim.animateTo(
				when (validScrollStrategy) {
					ScrollStrategy.EnterAlways,
					ScrollStrategy.EnterAlwaysCollapsed -> -toolbarState.minHeight.toFloat()

					ScrollStrategy.ExitUntilCollapsed -> return
				},
				if (duration == SPRING_BASED_DURATION) {
					spring()
				} else {
					tween(duration)
				}
			) {
				offsetYState.value = value.toInt()
			}
		}
	}

	@ExperimentalToolbarApi
	suspend fun expand(duration: Int = SPRING_BASED_DURATION) {
		coroutineScope {
			awaitAll(
				async { offsetExpand(duration) },
				async { toolbarState.expand(duration) }
			)
		}
	}

	@ExperimentalToolbarApi
	suspend fun collapse(duration: Int = SPRING_BASED_DURATION) {
		coroutineScope {
			awaitAll(
				async { offsetCollapse(duration) },
				async { toolbarState.collapse(duration) }
			)
		}
	}
}

private class CollapsingToolbarScaffoldStateSaver :
	Saver<CollapsingToolbarScaffoldState, List<Any>> {

	override fun restore(value: List<Any>): CollapsingToolbarScaffoldState =
		CollapsingToolbarScaffoldState(
			CollapsingToolbarState(value[0] as Int),
			value[1] as Int
		).also {
			val restoredScrollStrategy = value[2] as String

			if (restoredScrollStrategy.isNotEmpty()) {
				it.scrollStrategy = ScrollStrategy.valueOf(restoredScrollStrategy)
			}
		}

	override fun SaverScope.save(value: CollapsingToolbarScaffoldState): List<Any> =
		listOf(
			value.toolbarState.height,
			value.offsetY,
			value.scrollStrategy?.name.orEmpty()
		)
}

@Composable
fun rememberCollapsingToolbarScaffoldState(
	toolbarState: CollapsingToolbarState = rememberCollapsingToolbarState()
): CollapsingToolbarScaffoldState {
	return rememberSaveable(toolbarState, saver = CollapsingToolbarScaffoldStateSaver()) {
		CollapsingToolbarScaffoldState(toolbarState)
	}
}

interface CollapsingToolbarScaffoldScope {
	@ExperimentalToolbarApi
	fun Modifier.align(alignment: Alignment): Modifier
}

@Composable
fun CollapsingToolbarScaffold(
	modifier: Modifier,
	state: CollapsingToolbarScaffoldState,
	scrollStrategy: ScrollStrategy,
	snapConfig: SnapConfig? = null,
	enabled: Boolean = true,
	enabledWhenBodyUnfilled: Boolean = true,
	toolbarModifier: Modifier = Modifier,
	toolbarClipToBounds: Boolean = true,
	toolbarScrollable: Boolean = false,
	toolbar: @Composable CollapsingToolbarScope.() -> Unit,
	body: @Composable CollapsingToolbarScaffoldScope.() -> Unit
) {
	val flingBehavior = ScrollableDefaults.flingBehavior()
	val layoutDirection = LocalLayoutDirection.current

	val nestedScrollConnection = remember(scrollStrategy, state) {
		scrollStrategy.create(state, flingBehavior, snapConfig)
	}

	val toolbarState = state.toolbarState
	val toolbarScrollState = rememberScrollState()

	var isBodyUnfilled by remember { mutableStateOf(true) }

	handleToolbarExpandWhenBodyUnfilled(enabledWhenBodyUnfilled, isBodyUnfilled, state)

	Layout(
		content = {
			CollapsingToolbar(
				modifier = toolbarModifier,
				clipToBounds = toolbarClipToBounds,
				collapsingToolbarState = toolbarState
			) {
				ToolbarScrollableBox(
					enabled,
					toolbarScrollable,
					toolbarState,
					toolbarScrollState
				)

				toolbar()
			}

			BodyScrollableBox(
				enabled = enabled,
				toolbarScrollState = toolbarScrollState
			)
			CollapsingToolbarScaffoldScopeInstance.body()
		},
		modifier = modifier
			.collapsingToolbarScaffoldNestedScroll(
				enabled,
				enabledWhenBodyUnfilled,
				isBodyUnfilled,
				nestedScrollConnection
			)
	) { measurables, constraints ->
		check(measurables.size >= 2) {
			"the number of children should be at least 2: toolbar, (at least one) body"
		}

		val toolbarConstraints = constraints.copy(
			minWidth = 0,
			minHeight = 0,
            maxHeight = Int.MAX_VALUE,
		)
		val bodyConstraints = constraints.copy(
			minWidth = 0,
			minHeight = 0,
			maxHeight = when (scrollStrategy) {
				ScrollStrategy.ExitUntilCollapsed ->
					(constraints.maxHeight - toolbarState.minHeight).coerceAtLeast(0)

				ScrollStrategy.EnterAlways, ScrollStrategy.EnterAlwaysCollapsed ->
					constraints.maxHeight
			}
		)

		val toolbarPlaceable = measurables[0].measure(toolbarConstraints)
		val bodyScrollableBoxPlacable = measurables[1].measure(bodyConstraints)
		val bodyMeasurables = measurables.subList(2, measurables.size)
		val childrenAlignments = bodyMeasurables.map {
			(it.parentData as? ScaffoldParentData)?.alignment
		}
		val bodyPlaceables = bodyMeasurables.map {
			it.measure(bodyConstraints)
		}

		val toolbarHeight = toolbarPlaceable.height

		val width = max(
			toolbarPlaceable.width,
			bodyPlaceables.maxOfOrNull { it.width } ?: 0
		).coerceIn(constraints.minWidth, constraints.maxWidth)
		val bodyChildMaxHeight = bodyPlaceables.maxOfOrNull { it.height } ?: 0
		val height = max(
			toolbarHeight,
			bodyChildMaxHeight
		).coerceIn(constraints.minHeight, constraints.maxHeight)
		val bodyMaxHeight = height - toolbarState.maxHeight

		isBodyUnfilled = bodyChildMaxHeight <= bodyMaxHeight

		layout(width, height) {
			bodyScrollableBoxPlacable.place(0, toolbarHeight + state.offsetY)
			bodyPlaceables.forEachIndexed { index, placeable ->
				val alignment = childrenAlignments[index]

				if (alignment == null) {
					placeable.placeRelative(0, toolbarHeight + state.offsetY)
				} else {
					val offset = alignment.align(
						size = IntSize(placeable.width, placeable.height),
						space = IntSize(width, height),
						layoutDirection = layoutDirection
					)
					placeable.place(offset)
				}
			}
			toolbarPlaceable.placeRelative(0, state.offsetY)
		}
	}
}

private fun Modifier.collapsingToolbarScaffoldNestedScroll(
	enabled: Boolean,
	enabledWhenBodyUnfilled: Boolean,
	isBodyUnfilled: Boolean,
	nestedScrollConnection: NestedScrollConnection
): Modifier = then(
	if (enabled) {
		if (enabledWhenBodyUnfilled.not()) {
			if (isBodyUnfilled) {
				Modifier
			} else {
				Modifier.nestedScroll(nestedScrollConnection)
			}
		} else {
			Modifier.nestedScroll(nestedScrollConnection)
		}
	} else {
		Modifier
	}
)

@SuppressLint("ComposableNaming")
@OptIn(ExperimentalToolbarApi::class)
@Composable
private fun handleToolbarExpandWhenBodyUnfilled(
	enabledWhenBodyUnfilled: Boolean,
	isBodyUnfilled: Boolean,
	state: CollapsingToolbarScaffoldState
) {
	if (enabledWhenBodyUnfilled.not()) {
		LaunchedEffect(isBodyUnfilled) {
			if (isBodyUnfilled && state.totalProgress != 1f) {
				state.expand()
			}
		}
	}
}

@Composable
private fun BodyScrollableBox(
	enabled: Boolean,
	toolbarScrollState: ScrollState
) {
	if (enabled) {
		Box(
			modifier = Modifier
				.fillMaxSize()
				.verticalScroll(state = toolbarScrollState)
		)
	}
}

@Composable
private fun ToolbarScrollableBox(
	enabled: Boolean,
	toolbarScrollable: Boolean,
	toolbarState: CollapsingToolbarState,
	toolbarScrollState: ScrollState
) {
	val toolbarScrollableEnabled = enabled && toolbarScrollable

	if (toolbarScrollableEnabled && toolbarState.height != Constraints.Infinity) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.height(with(LocalDensity.current) { toolbarState.height.toDp() })
				.verticalScroll(state = toolbarScrollState)
		)
	}
}

internal object CollapsingToolbarScaffoldScopeInstance : CollapsingToolbarScaffoldScope {
	@ExperimentalToolbarApi
	override fun Modifier.align(alignment: Alignment): Modifier =
		this.then(ScaffoldChildAlignmentModifier(alignment))
}

private class ScaffoldChildAlignmentModifier(
	private val alignment: Alignment
) : ParentDataModifier {
	override fun Density.modifyParentData(parentData: Any?): Any {
		return (parentData as? ScaffoldParentData) ?: ScaffoldParentData(alignment)
	}
}

private data class ScaffoldParentData(
	var alignment: Alignment? = null
)
