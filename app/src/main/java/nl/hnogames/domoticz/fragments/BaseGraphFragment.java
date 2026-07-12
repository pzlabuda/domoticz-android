package nl.hnogames.domoticz.fragments;

import android.content.Context;
import android.content.res.Resources;
import android.util.TypedValue;

import androidx.fragment.app.Fragment;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.util.Calendar;
import java.util.Locale;

import nl.hnogames.domoticz.R;
import nl.hnogames.domoticz.interfaces.DomoticzFragmentListener;

public abstract class BaseGraphFragment extends Fragment implements DomoticzFragmentListener {

    protected String range = "day";

    /**
     * Returns true if the value should be plotted on the graph.
     * For non-"day" ranges (weekly/monthly/yearly aggregated data), values of exactly 0
     * are treated as missing data (e.g., monitoring tool was down) and skipped to prevent
     * the graph from dropping to zero and distorting the y-axis scale.
     */
    protected boolean isValidGraphValue(String value) {
        if (value == null || value.isEmpty()) return false;
        if (range.equals("day") || range.equals("minute")) return true;
        try {
            return Float.parseFloat(value) != 0.0f;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /**
     * Returns CUBIC_BEZIER for day/minute ranges (densely-sampled data → smooth curves look natural)
     * and LINEAR for week/month/year (sparse aggregated points → bezier overshoots and looks wavy).
     */
    protected LineDataSet.Mode smoothMode() {
        return (range.equals("day") || range.equals("minute"))
                ? LineDataSet.Mode.CUBIC_BEZIER
                : LineDataSet.Mode.LINEAR;
    }

    /** Applies shared visual configuration to a LineChart. */
    protected void setupChartAppearance(LineChart chart, Context context) {
        TypedValue typedValue = new TypedValue();
        Resources.Theme theme = context.getTheme();
        theme.resolveAttribute(R.attr.graphTextColor, typedValue, true);

        XAxis xAxis = chart.getXAxis();
        YAxis yAxis = chart.getAxisLeft();

        xAxis.setTextColor(typedValue.data);
        yAxis.setTextColor(typedValue.data);
        chart.getLegend().setTextColor(typedValue.data);

        Legend legend = chart.getLegend();
        legend.setWordWrapEnabled(true);
        legend.setForm(Legend.LegendForm.CIRCLE);

        chart.getDescription().setEnabled(false);
        chart.setDrawGridBackground(false);
        xAxis.setDrawGridLines(false);
        chart.getAxisRight().setEnabled(false);
        YAxis yAxisRight = chart.getAxisRight();
        yAxisRight.setTextColor(typedValue.data);
        yAxisRight.setDrawGridLines(false);
        chart.setDragDecelerationFrictionCoef(0.9f);
        chart.setDragEnabled(true);
        chart.setScaleEnabled(true);
        chart.setAutoScaleMinMaxEnabled(true);
        chart.setHighlightPerDragEnabled(true);
        xAxis.setLabelRotationAngle(90);
        xAxis.setLabelCount(15);
    }

    protected ValueFormatter createHourMinuteFormatter() {
        return new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                Calendar calendar = Calendar.getInstance();
                calendar.setTimeInMillis((long) value);
                return String.format(Locale.getDefault(), "%02d:%02d",
                        calendar.get(Calendar.HOUR_OF_DAY),
                        calendar.get(Calendar.MINUTE));
            }
        };
    }

    protected ValueFormatter createDateTimeFormatter() {
        return new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                Calendar calendar = Calendar.getInstance();
                calendar.setTimeInMillis((long) value);
                int mMonth = calendar.get(Calendar.MONTH) + 1;
                int mDay = calendar.get(Calendar.DAY_OF_MONTH);
                int mHours = calendar.get(Calendar.HOUR_OF_DAY);
                int mMinutes = calendar.get(Calendar.MINUTE);
                if (mHours <= 0 && mMinutes <= 0)
                    return String.format(Locale.getDefault(), "%d/%d", mDay, mMonth);
                return String.format(Locale.getDefault(), "%d/%d %02d:%02d",
                        mDay, mMonth, mHours, mMinutes);
            }
        };
    }
}
