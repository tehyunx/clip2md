package com.gumo.clip2md

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ENTRY_ID = "entry_id"
        const val EXTRA_MERGED_MARKDOWN = "merged_markdown"
    }

    private lateinit var entries: MutableList<HistoryEntry>
    private lateinit var listView: ListView
    private lateinit var emptyText: TextView
    private lateinit var adapter: BaseAdapter
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    // Tracks the order items were checked in, keyed by entry id — so merge
    // can follow tap order rather than list/timestamp order.
    private val selectionOrder = LinkedHashSet<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        listView = findViewById(R.id.listHistory)
        emptyText = findViewById(R.id.emptyText)
        entries = HistoryStore.list(this).toMutableList()

        adapter = object : BaseAdapter() {
            override fun getCount() = entries.size
            override fun getItem(position: Int) = entries[position]
            override fun getItemId(position: Int) = entries[position].id

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_history, parent, false)
                val entry = entries[position]
                view.findViewById<TextView>(R.id.itemTitle).text = entry.title
                view.findViewById<TextView>(R.id.itemTime).text = fmt.format(Date(entry.timestamp))

                val checked = listView.isItemChecked(position)
                view.findViewById<CheckBox>(R.id.itemCheck).isChecked = checked

                val orderText = view.findViewById<TextView>(R.id.itemOrder)
                val order = selectionOrder.indexOf(entry.id)
                if (checked && order >= 0) {
                    orderText.text = (order + 1).toString()
                    orderText.visibility = View.VISIBLE
                } else {
                    orderText.visibility = View.GONE
                }
                return view
            }
        }
        listView.adapter = adapter
        refreshEmptyState()

        listView.setOnItemClickListener { _, _, position, _ ->
            val entry = entries[position]
            val result = Intent()
            result.putExtra(EXTRA_ENTRY_ID, entry.id)
            setResult(Activity.RESULT_OK, result)
            finish()
        }

        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE_MODAL
        listView.setMultiChoiceModeListener(object : AbsListView.MultiChoiceModeListener {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                selectionOrder.clear()
                mode.menuInflater.inflate(R.menu.history_cab, menu)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

            override fun onItemCheckedStateChanged(
                mode: ActionMode, position: Int, id: Long, checked: Boolean
            ) {
                if (checked) selectionOrder.add(id) else selectionOrder.remove(id)
                mode.title = "${listView.checkedItemCount}개 선택됨"
                adapter.notifyDataSetChanged()
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                return when (item.itemId) {
                    R.id.action_merge -> {
                        val selected = selectionOrder.mapNotNull { id -> entries.find { it.id == id } }
                        val merged = selected.joinToString("\n\n---\n\n") { entry ->
                            HistoryStore.loadMarkdown(this@HistoryActivity, entry.id) ?: ""
                        }
                        val result = Intent()
                        result.putExtra(EXTRA_MERGED_MARKDOWN, merged)
                        setResult(Activity.RESULT_OK, result)
                        mode.finish()
                        finish()
                        true
                    }
                    R.id.action_delete -> {
                        val idsToDelete = selectionOrder.toList()
                        AlertDialog.Builder(this@HistoryActivity)
                            .setMessage("${idsToDelete.size}개 항목을 삭제할까요?")
                            .setPositiveButton("삭제") { _, _ ->
                                HistoryStore.delete(this@HistoryActivity, idsToDelete)
                                entries.removeAll { it.id in idsToDelete }
                                adapter.notifyDataSetChanged()
                                refreshEmptyState()
                                mode.finish()
                            }
                            .setNegativeButton("취소", null)
                            .show()
                        true
                    }
                    else -> false
                }
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                selectionOrder.clear()
            }
        })
    }

    private fun refreshEmptyState() {
        if (entries.isEmpty()) {
            listView.visibility = View.GONE
            emptyText.visibility = View.VISIBLE
        } else {
            listView.visibility = View.VISIBLE
            emptyText.visibility = View.GONE
        }
    }
}
