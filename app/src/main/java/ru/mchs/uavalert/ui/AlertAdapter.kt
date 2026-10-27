package ru.mchs.uavalert.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ru.mchs.uavalert.data.UavAlert
import ru.mchs.uavalert.databinding.ItemAlertBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Адаптер списка предупреждений об угрозе БПЛА. */
class AlertAdapter : ListAdapter<UavAlert, AlertAdapter.VH>(DIFF) {

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

    inner class VH(private val binding: ItemAlertBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(alert: UavAlert) = with(binding) {
            textTitle.text = alert.title
            textRegion.text = alert.region.ifBlank { "—" }
            textTime.text = dateFormat.format(Date(alert.timestamp))
            textText.text = alert.text
            root.setOnClickListener { itemClickListener?.invoke(alert) }
        }
    }

    var itemClickListener: ((UavAlert) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemAlertBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(getItem(position))

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<UavAlert>() {
            override fun areItemsTheSame(oldItem: UavAlert, newItem: UavAlert) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: UavAlert, newItem: UavAlert) =
                oldItem == newItem
        }
    }
}
