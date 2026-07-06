package com.gumo.clip2md

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ENTRY_ID = "entry_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        val entries = HistoryStore.list(this)
        val listView = findViewById<ListView>(R.id.listHistory)
        val emptyText = findViewById<TextView>(R.id.emptyText)

        if (entries.isEmpty()) {
            listView.visibility = View.GONE
            emptyText.visibility = View.VISIBLE
            return
        }

        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        listView.adapter = object : BaseAdapter() {
            override fun getCount() = entries.size
            override fun getItem(position: Int) = entries[position]
            override fun getItemId(position: Int) = entries[position].id

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_history, parent, false)
                val entry = entries[position]
                view.findViewById<TextView>(R.id.itemTitle).text = entry.title
                view.findViewById<TextView>(R.id.itemTime).text = fmt.format(Date(entry.timestamp))
                return view
            }
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val entry = entries[position]
            val result = Intent()
            result.putExtra(EXTRA_ENTRY_ID, entry.id)
            setResult(Activity.RESULT_OK, result)
            finish()
        }
    }
}
