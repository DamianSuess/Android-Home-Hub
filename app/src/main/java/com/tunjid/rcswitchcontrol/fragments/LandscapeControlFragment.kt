package com.tunjid.rcswitchcontrol.fragments

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.observe
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import com.google.android.material.button.MaterialButton
import com.tunjid.androidx.core.content.colorAt
import com.tunjid.androidx.navigation.Navigator
import com.tunjid.androidx.navigation.childStackNavigationController
import com.tunjid.androidx.recyclerview.ListManager
import com.tunjid.androidx.recyclerview.ListManagerBuilder
import com.tunjid.androidx.recyclerview.ListPlaceholder
import com.tunjid.androidx.recyclerview.adapterOf
import com.tunjid.androidx.view.util.spring
import com.tunjid.rcswitchcontrol.R
import com.tunjid.rcswitchcontrol.abstractclasses.BaseFragment
import com.tunjid.rcswitchcontrol.services.ServerNsdService
import com.tunjid.rcswitchcontrol.viewmodels.ControlViewModel
import com.tunjid.rcswitchcontrol.viewmodels.ProtocolKey

class LandscapeControlFragment : BaseFragment(R.layout.fragment_control_landscape), Navigator.TagProvider {

    private val innerNavigator by childStackNavigationController(R.id.child_fragment_container)

    private val viewModel by activityViewModels<ControlViewModel>()

    private var listManager: ListManager<HeaderViewHolder, ListPlaceholder<*>>? = null

    private val host by lazy { requireActivity().getString(R.string.host) }

    private val devices by lazy { requireActivity().getString(R.string.devices) }

    override val stableTag: String = "ControlHeaders"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel.listen(ControlViewModel.State::class.java).observe(this, this::onPayloadReceived)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listManager = ListManagerBuilder<HeaderViewHolder, ListPlaceholder<*>>()
                .withRecyclerView(view.findViewById(R.id.list))
                .withCustomLayoutManager(FlexboxLayoutManager(context).apply {
                    alignItems = AlignItems.STRETCH
                    flexDirection = FlexDirection.ROW
                    justifyContent = JustifyContent.CENTER
                })
                .withAdapter(adapterOf(
                        itemsSource = this::items,
                        viewHolderCreator = { parent, _ ->
                            HeaderViewHolder(parent.context, this::onHeaderHighlighted)
                        },
                        viewHolderBinder = { holder, key, _ -> holder.bind(key) }
                ))
                .build()
    }

    private fun items(): List<Any> = when {
        ServerNsdService.isServer -> listOf(host, devices)
        else -> listOf(devices)
    } + viewModel.keys

    private fun onHeaderHighlighted(key: Any) = when (key) {
        host -> HostFragment.newInstance()
        devices -> DevicesFragment.newInstance()
        is ProtocolKey -> RecordFragment.commandInstance(ProtocolKey(key.name))
        else -> null
    }?.let { innerNavigator.push(it); Unit } ?: Unit

    private fun onPayloadReceived(state: ControlViewModel.State) {
        if (state !is ControlViewModel.State.Commands || !state.isNew) return
        listManager?.notifyDataSetChanged()
    }

    companion object {
        fun newInstance(): LandscapeControlFragment =
                LandscapeControlFragment().apply { arguments = bundleOf() }
    }
}

class HeaderViewHolder(context: Context, onFocused: (Any) -> Unit) : RecyclerView.ViewHolder(MaterialButton(context)) {

    val text = itemView as MaterialButton
    var key: Any? = null

    init {
        text.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            cornerRadius = context.resources.getDimensionPixelSize(R.dimen.quadruple_margin)
            backgroundTintList = ColorStateList.valueOf(context.colorAt(R.color.app_background))
            setOnFocusChangeListener { _, hasFocus ->
                spring(SpringAnimation.SCALE_Y).animateToFinalPosition(if (hasFocus) 1.1F else 1F)
                spring(SpringAnimation.SCALE_X).animateToFinalPosition(if (hasFocus) 1.1F else 1F)
                val frozen = key
                if (hasFocus && frozen != null) onFocused(frozen)
            }
        }
    }

    fun bind(key: Any) {
        this.key = key
        text.text = when (key) {
            is ProtocolKey -> key.title
            is String -> key
            else -> "unknown"
        }
    }

}
