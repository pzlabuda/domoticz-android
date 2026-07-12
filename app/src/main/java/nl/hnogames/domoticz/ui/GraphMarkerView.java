package nl.hnogames.domoticz.ui;

import android.content.Context;
import android.widget.TextView;

import com.github.mikephil.charting.components.MarkerView;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.utils.MPPointF;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import nl.hnogames.domoticz.R;

public class GraphMarkerView extends MarkerView {

    private final TextView tvDate;
    private final TextView tvValue;
    private final boolean isDayRange;

    public GraphMarkerView(Context context, boolean isDayRange) {
        super(context, R.layout.marker_graph_view);
        tvDate = findViewById(R.id.marker_date);
        tvValue = findViewById(R.id.marker_value);
        this.isDayRange = isDayRange;
    }

    @Override
    public void refreshContent(Entry e, Highlight highlight) {
        SimpleDateFormat sdf = new SimpleDateFormat(
                isDayRange ? "HH:mm" : "dd/MM HH:mm", Locale.getDefault());
        tvDate.setText(sdf.format(new Date((long) e.getX())));
        tvValue.setText(String.format(Locale.getDefault(), "%.2f", e.getY()));
        super.refreshContent(e, highlight);
    }

    @Override
    public MPPointF getOffset() {
        return new MPPointF(-(getWidth() / 2f), -getHeight());
    }
}
