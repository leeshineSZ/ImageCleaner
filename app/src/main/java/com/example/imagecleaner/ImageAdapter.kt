package com.example.imagecleaner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ImageAdapter(
    private val images: MutableList<ImageData>,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<ImageAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivImage: ImageView = view.findViewById(R.id.ivImage)
        val cbSelect: CheckBox = view.findViewById(R.id.cbSelect)
        val overlay: View = view.findViewById(R.id.overlay)
        val tvDate: TextView = view.findViewById(R.id.tvDate)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_image, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val image = images[position]

        Glide.with(holder.itemView.context)
            .load(image.uri)
            .centerCrop()
            .into(holder.ivImage)

        holder.tvDate.text = dateFormat.format(Date(image.dateAdded * 1000))
        holder.cbSelect.setOnCheckedChangeListener(null)
        holder.cbSelect.isChecked = image.isSelected

        val toggle = {
            image.isSelected = !image.isSelected
            holder.cbSelect.isChecked = image.isSelected
            holder.overlay.visibility = if (image.isSelected) View.VISIBLE else View.GONE
            onSelectionChanged()
        }

        holder.cbSelect.setOnCheckedChangeListener { _, _ -> toggle() }
        holder.ivImage.setOnClickListener { toggle() }

        holder.overlay.visibility = if (image.isSelected) View.VISIBLE else View.GONE
    }

    override fun getItemCount() = images.size

    fun getSelectedImages(): List<ImageData> = images.filter { it.isSelected }

    fun removeImages(toRemove: List<ImageData>) {
        images.removeAll(toRemove.toSet())
        notifyDataSetChanged()
    }

    fun selectAll() {
        images.forEach { it.isSelected = true }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun clearSelection() {
        images.forEach { it.isSelected = false }
        notifyDataSetChanged()
        onSelectionChanged()
    }
}
