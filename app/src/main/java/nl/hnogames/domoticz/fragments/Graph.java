package nl.hnogames.domoticz.fragments;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.afollestad.materialdialogs.MaterialDialog;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.io.File;
import java.io.FileOutputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import nl.hnogames.domoticz.GraphActivity;
import nl.hnogames.domoticz.R;
import nl.hnogames.domoticz.helpers.StaticHelper;
import nl.hnogames.domoticz.ui.GraphMarkerView;
import nl.hnogames.domoticz.utils.SharedPrefUtil;
import nl.hnogames.domoticz.utils.UsefulBits;
import nl.hnogames.domoticzapi.Containers.GraphPointInfo;
import nl.hnogames.domoticzapi.Interfaces.GraphDataReceiver;

public class Graph extends BaseGraphFragment {

    @SuppressWarnings("unused")
    private static final String TAG = Graph.class.getSimpleName();
    private final int steps = 1;
    private Context context;
    private int idx = 0;
    private String type = "temp";
    private String axisYLabel = "Temp";

    private boolean enableFilters = false;
    private List<String> lineLabels;
    private List<String> filterLabels;

    private ArrayList<GraphPointInfo> mGraphList;
    private LineChart chart;
    private View root;
    private Integer[] selectedFilters;
    private SharedPrefUtil mSharedPrefs;
    private ChipGroup rangeChipGroup;

    private boolean isInitialLoad = true;
    private boolean isRevertingRange = false;
    private String previousRange = "day";
    private LinearLayout statsBar;
    private TextView statMin, statAvg, statMax;
    private View emptyState;
    private float currentThreshold = 0f;
    private boolean needsRightAxis = false;
    private Chip compareChip;
    private boolean compareEnabled = false;
    private ArrayList<GraphPointInfo> mCompareGraphList;

    @Override
    public void onConnectionFailed() {
    }

    @Override
    public void onConnectionOk() {
        if (getView() != null) {
            getGraphs();
        }
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        this.context = context;
        mSharedPrefs = new SharedPrefUtil(context);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        Bundle data = getActivity().getIntent().getExtras();
        if (data != null) {
            idx = data.getInt("IDX");
            range = data.getString("RANGE", "day");
            type = data.getString("TYPE", "temp");
            axisYLabel = data.getString("TITLE", "Temp");
        }
    }

    @SuppressLint("InflateParams")
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        root = inflater.inflate(R.layout.dialog_graph, null);

        chart = root.findViewById(R.id.chart);
        statsBar = root.findViewById(R.id.stats_bar);
        statMin = root.findViewById(R.id.stat_min);
        statAvg = root.findViewById(R.id.stat_avg);
        statMax = root.findViewById(R.id.stat_max);
        emptyState = root.findViewById(R.id.empty_state);
        setupChartAppearance(chart, context);

        XAxis xAxis = chart.getXAxis();
        if (range.equals("day")) {
            xAxis.setValueFormatter(createHourMinuteFormatter());
        } else {
            xAxis.setValueFormatter(createDateTimeFormatter());
        }

        setupYAxisFormatter();
        updateMarkerView();

        rangeChipGroup = root.findViewById(R.id.range_chip_group);
        compareChip = root.findViewById(R.id.chip_compare);
        compareChip.setOnCheckedChangeListener((buttonView, isChecked) -> {
            compareEnabled = isChecked;
            if (compareEnabled) {
                getCompareGraphs();
            } else {
                mCompareGraphList = null;
                LineData columnData = generateData(root);
                if (columnData != null) {
                    chart.setData(columnData);
                    chart.invalidate();
                    chart.setVisibility(View.VISIBLE);
                }
            }
        });
        compareChip.setVisibility(View.GONE);

        // Week range is only available for counter and rain sensor types
        boolean hasWeekData = type.equals("counter") || type.equals("rain");
        if (!hasWeekData) {
            root.findViewById(R.id.chip_week).setVisibility(View.GONE);
        }

        selectChipForRange(range);

        rangeChipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty() || isRevertingRange) return;
            int id = checkedIds.get(0);
            String newRange;
            if (id == R.id.chip_day) newRange = "day";
            else if (id == R.id.chip_week) newRange = "week";
            else if (id == R.id.chip_month) newRange = "month";
            else newRange = "year";

            if (!newRange.equals(range)) {
                previousRange = range;
                range = newRange;
                if (range.equals("day")) {
                    xAxis.setValueFormatter(createHourMinuteFormatter());
                } else {
                    xAxis.setValueFormatter(createDateTimeFormatter());
                }
                updateMarkerView();
                chart.setVisibility(View.GONE);
                getGraphs();
            }
        });

        loadThreshold();
        applyThresholdToChart();
        getGraphs();
        return root;
    }

    private void updateMarkerView() {
        GraphMarkerView markerView = new GraphMarkerView(context, range.equals("day") || range.equals("minute"));
        markerView.setChartView(chart);
        chart.setMarker(markerView);
    }

    private void selectChipForRange(String r) {
        int chipId;
        switch (r) {
            case "week":
                chipId = R.id.chip_week;
                break;
            case "month":
                chipId = R.id.chip_month;
                break;
            case "year":
                chipId = R.id.chip_year;
                break;
            default:
                chipId = R.id.chip_day;
                break;
        }
        rangeChipGroup.check(chipId);
    }

    private void setupYAxisFormatter() {
        final String unit;
        switch (type) {
            case "temp":
                unit = "°";
                break;
            case "Percentage":
                unit = "%";
                break;
            case "counter":
                unit = " kWh";
                break;
            default:
                unit = "";
                break;
        }
        if (!unit.isEmpty()) {
            chart.getAxisLeft().setValueFormatter(new com.github.mikephil.charting.formatter.ValueFormatter() {
                @Override
                public String getFormattedValue(float value) {
                    return String.format(Locale.getDefault(), "%.1f%s", value, unit);
                }
            });
        }
    }

    private void getGraphs() {
        chart.setVisibility(View.GONE);
        if (emptyState != null) emptyState.setVisibility(View.GONE);
        new Thread() {
            @Override
            public void run() {
                StaticHelper.getDomoticz(context).getGraphData(idx, range, type, new GraphDataReceiver() {
                    @Override
                    public void onReceive(ArrayList<GraphPointInfo> grphPoints) {
                        try {
                            mGraphList = grphPoints;
                            if (compareEnabled) {
                                mCompareGraphList = null;
                            }
                            LineData columnData = generateData(root);
                            if (columnData != null && columnData.getDataSetCount() > 0) {
                                isInitialLoad = false;
                                chart.setData(columnData);
                                chart.invalidate();

                                if (getActivity() != null) {
                                    getActivity().runOnUiThread(() -> {
                                        if (needsRightAxis) {
                                            chart.getAxisRight().setEnabled(true);
                                            chart.getAxisRight().setValueFormatter(new ValueFormatter() {
                                                @Override
                                                public String getFormattedValue(float value) {
                                                    return String.format(Locale.getDefault(), "%.0f", value);
                                                }
                                            });
                                        } else {
                                            chart.getAxisRight().setEnabled(false);
                                        }
                                        if (emptyState != null) emptyState.setVisibility(View.GONE);
                                        chart.setVisibility(View.VISIBLE);
                                        chart.animateX(1000);
                                        applyThresholdToChart();
                                        updateStats();
                                        if (getActivity() != null)
                                            getActivity().invalidateOptionsMenu();
                                    });
                                }
                                if (compareEnabled) {
                                    getCompareGraphs();
                                }
                            } else {
                                if (getActivity() != null) {
                                    getActivity().runOnUiThread(() -> {
                                        chart.setVisibility(View.GONE);
                                        if (emptyState != null) emptyState.setVisibility(View.VISIBLE);
                                        if (statsBar != null) statsBar.setVisibility(View.GONE);
                                    });
                                }
                            }
                        } catch (Exception ex) {
                            if (ex.getMessage() != null)
                                Log.e(this.getClass().getSimpleName(), ex.getMessage());
                        }
                    }

                    @Override
                    public void onError(Exception ex) {
                        if (!isAdded()) return;
                        if (isInitialLoad) {
                            ((GraphActivity) getActivity()).noGraphFound();
                        } else {
                            getActivity().runOnUiThread(() -> {
                                Toast.makeText(context, R.string.no_data_for_range, Toast.LENGTH_SHORT).show();
                                isRevertingRange = true;
                                range = previousRange;
                                XAxis xAxis = chart.getXAxis();
                                if (range.equals("day")) {
                                    xAxis.setValueFormatter(createHourMinuteFormatter());
                                } else {
                                    xAxis.setValueFormatter(createDateTimeFormatter());
                                }
                                updateMarkerView();
                                selectChipForRange(range);
                                isRevertingRange = false;
                                chart.setVisibility(View.VISIBLE);
                            });
                        }
                    }
                });
            }
        }.start();
    }

    public ActionBar getActionBar() {
        try {
            return ((AppCompatActivity) getActivity().getApplicationContext()).getSupportActionBar();
        } catch (Exception ex) {
            return null;
        }
    }

    @SuppressWarnings("SpellCheckingInspection")
    private LineData generateData(View view) {
        try {
            List<LineDataSet> entries = new ArrayList<>();

            List<Entry> valuest = new ArrayList<>();
            List<Entry> valuestMin = new ArrayList<>();
            List<Entry> valuestMax = new ArrayList<>();

            List<Entry> valuesse = new ArrayList<>();
            List<Entry> valueshu = new ArrayList<>();
            List<Entry> valuesba = new ArrayList<>();
            List<Entry> valuesc = new ArrayList<>();

            List<Entry> valuesv = new ArrayList<>();
            List<Entry> valuesv2 = new ArrayList<>();
            List<Entry> valuesvMin = new ArrayList<>();
            List<Entry> valuesvMax = new ArrayList<>();

            List<Entry> valueeu = new ArrayList<>();
            List<Entry> valueeg = new ArrayList<>();

            List<Entry> valuessp = new ArrayList<>();
            List<Entry> valuesdi = new ArrayList<>();
            List<Entry> valuesuv = new ArrayList<>();
            List<Entry> valuesu = new ArrayList<>();
            List<Entry> valuesmm = new ArrayList<>();

            List<Entry> valuesco2 = new ArrayList<>();
            List<Entry> valuesco2min = new ArrayList<>();
            List<Entry> valuesco2max = new ArrayList<>();

            List<Entry> valuesLux = new ArrayList<>();
            List<Entry> valuesLuxmin = new ArrayList<>();
            List<Entry> valuesLuxmax = new ArrayList<>();
            List<Entry> valuesLuxAvg = new ArrayList<>();

            boolean addHumidity = false;
            boolean addBarometer = false;
            boolean addTemperature = false;
            boolean addTemperatureRange = false;
            boolean addSetpoint = false;
            boolean addCounter = false;
            boolean addPercentage = false;
            boolean addSecondPercentage = false;
            boolean addPercentageRange = false;
            boolean addSunPower = false;
            boolean addDirection = false;
            boolean addSpeed = false;
            boolean addRain = false;
            boolean addCO2 = false;
            boolean addCO2Min = false;
            boolean addCO2Max = false;
            boolean addUsage = false;
            boolean addPowerUsage = false;
            boolean addPowerDelivery = false;
            boolean addLux = false;
            boolean addLuxMin = false;
            boolean addLuxMax = false;
            boolean addLuxAvg = false;

            Calendar mydate = Calendar.getInstance();

            int stepcounter = 0;
            for (GraphPointInfo g : this.mGraphList) {
                stepcounter++;
                if (stepcounter == this.steps) {
                    stepcounter = 0;

                    try {
                        try {
                            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
                            mydate.setTime(sdf.parse(g.getDateTime()));
                        } catch (ParseException e) {
                            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
                            mydate.setTime(sdf.parse(g.getDateTime()));
                        }

                        if (!Float.isNaN(g.getTemperature())) {
                            addTemperature = true;
                            valuest.add(new Entry(mydate.getTimeInMillis(), g.getTemperature()));

                            if (g.hasTemperatureRange()) {
                                addTemperatureRange = true;
                                valuestMax.add(new Entry(mydate.getTimeInMillis(), g.getTemperatureMax()));
                                valuestMin.add(new Entry(mydate.getTimeInMillis(), g.getTemperatureMin()));
                            }
                        }

                        if (!Float.isNaN(g.getSetPoint())) {
                            addSetpoint = true;
                            valuesse.add(new Entry(mydate.getTimeInMillis(), g.getSetPoint()));
                        }

                        if (isValidGraphValue(g.getBarometer())) {
                            addBarometer = true;
                            try {
                                valuesba.add(new Entry(mydate.getTimeInMillis(), Integer.parseInt(g.getBarometer())));
                            } catch (Exception ex) {
                                valuesba.add(new Entry(mydate.getTimeInMillis(), Float.parseFloat(g.getBarometer())));
                            }
                        }

                        if (isValidGraphValue(g.getHumidity())) {
                            addHumidity = true;
                            try {
                                valueshu.add(new Entry(mydate.getTimeInMillis(), Integer.parseInt(g.getHumidity())));
                            } catch (Exception ex) {
                                valueshu.add(new Entry(mydate.getTimeInMillis(), Float.parseFloat(g.getHumidity())));
                            }
                        }

                        if (isValidGraphValue(g.getValue())) {
                            addPercentage = true;
                            valuesv.add(new Entry(mydate.getTimeInMillis(), Float.parseFloat(g.getValue())));
                            if (g.hasValueRange()) {
                                addPercentageRange = true;
                                valuesvMin.add(new Entry(mydate.getTimeInMillis(), Float.parseFloat(g.getValueMin())));
                                valuesvMax.add(new Entry(mydate.getTimeInMillis(), Float.parseFloat(g.getValueMax())));
                            }
                        }

                        if (isValidGraphValue(g.getSecondValue())) {
                            addSecondPercentage = true;
                            valuesv2.add(new Entry(mydate.getTimeInMillis(), Float.parseFloat(g.getSecondValue())));
                        }

                        if (isValidGraphValue(g.getPowerDelivery())) {
                            addPowerDelivery = true;
                            valueeg.add(new Entry(mydate.getTimeInMillis(), Float.parseFloat(g.getPowerDelivery())));
                        }

                        if (isValidGraphValue(g.getPowerUsage())) {
                            addPowerUsage = true;
                            valueeu.add(new Entry(mydate.getTimeInMillis(), Float.parseFloat(g.getPowerUsage())));
                        }

                        if (isValidGraphValue(g.getCounter())) {
                            addCounter = true;
                            valuesc.add(new Entry(mydate.getTimeInMillis(), Float.parseFloat(g.getCounter())));
                        }

                        if (isValidGraphValue(g.getSpeed())) {
                            addSpeed = true;
                            valuessp.add(new Entry(mydate.getTimeInMillis(), Float.parseFloat(g.getSpeed())));
                        }

                        if (isValidGraphValue(g.getDirection())) {
                            addDirection = true;
                            valuesdi.add(new Entry(mydate.getTimeInMillis(), Float.parseFloat(g.getDirection())));
                        }

                        if (isValidGraphValue(g.getSunPower())) {
                            addSunPower = true;
                            valuesuv.add(new Entry(mydate.getTimeInMillis(), Float.parseFloat(g.getSunPower())));
                        }

                        if (isValidGraphValue(g.getUsage())) {
                            addUsage = true;
                            valuesu.add(new Entry(mydate.getTimeInMillis(), Float.parseFloat(g.getUsage())));
                        }

                        if (isValidGraphValue(g.getRain())) {
                            addRain = true;
                            valuesmm.add(new Entry(mydate.getTimeInMillis(), Float.parseFloat(g.getRain())));
                        }

                        if (isValidGraphValue(g.getCo2())) {
                            addCO2 = true;
                            valuesco2.add(new Entry(mydate.getTimeInMillis(), Float.parseFloat(g.getCo2())));
                        }

                        if (isValidGraphValue(g.getCo2Min())) {
                            addCO2Min = true;
                            valuesco2min.add(new Entry(mydate.getTimeInMillis(), Float.parseFloat(g.getCo2Min())));
                        }

                        if (isValidGraphValue(g.getCo2Max())) {
                            addCO2Max = true;
                            valuesco2max.add(new Entry(mydate.getTimeInMillis(), Float.parseFloat(g.getCo2Max())));
                        }

                        if (isValidGraphValue(g.getLux())) {
                            addLux = true;
                            valuesLux.add(new Entry(mydate.getTimeInMillis(), Float.parseFloat(g.getLux())));
                        }

                        if (isValidGraphValue(g.getLuxMin())) {
                            addLuxMin = true;
                            valuesLuxmin.add(new Entry(mydate.getTimeInMillis(), Float.parseFloat(g.getLuxMin())));
                        }

                        if (isValidGraphValue(g.getLuxMax())) {
                            addLuxMax = true;
                            valuesLuxmax.add(new Entry(mydate.getTimeInMillis(), Float.parseFloat(g.getLuxMax())));
                        }

                        if (isValidGraphValue(g.getLuxAvg())) {
                            addLuxAvg = true;
                            valuesLuxAvg.add(new Entry(mydate.getTimeInMillis(), Float.parseFloat(g.getLuxAvg())));
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }

            needsRightAxis = addTemperature && (addHumidity || addBarometer);

            if ((addTemperature && !enableFilters) ||
                    (filterLabels != null && filterLabels.contains(((TextView) view.findViewById(R.id.legend_temperature)).getText().toString()))) {
                LineDataSet dataSet = new LineDataSet(valuest, ((TextView) view.findViewById(R.id.legend_temperature)).getText().toString());
                dataSet.setColor(ContextCompat.getColor(context, R.color.material_blue_600));
                dataSet.setDrawCircles(false);
                dataSet.setLineWidth(2);
                dataSet.setMode(smoothMode());
                dataSet.setDrawFilled(true);
                dataSet.setFillAlpha(40);
                dataSet.setFillColor(ContextCompat.getColor(context, R.color.material_blue_600));
                entries.add(dataSet);

                if ((addSetpoint && !enableFilters) ||
                        (filterLabels != null && filterLabels.contains(((TextView) view.findViewById(R.id.legend_set_point)).getText().toString()))) {
                    dataSet = new LineDataSet(valuesse, ((TextView) view.findViewById(R.id.legend_set_point)).getText().toString());
                    dataSet.setColor(ContextCompat.getColor(context, R.color.material_pink_600));
                    dataSet.setDrawCircles(false);
                    dataSet.setMode(LineDataSet.Mode.LINEAR);
                    entries.add(dataSet);
                }

                if (addTemperatureRange) {
                    dataSet = new LineDataSet(valuestMax, "Max");
                    dataSet.setLineWidth(2);
                    dataSet.setColor(ContextCompat.getColor(context, com.mikepenz.materialize.R.color.md_blue_50));
                    dataSet.setDrawCircles(false);
                    dataSet.setMode(smoothMode());
                    dataSet.setDrawFilled(true);
                    dataSet.setFillColor(com.mikepenz.materialize.R.color.md_blue_300);
                    entries.add(dataSet);

                    dataSet = new LineDataSet(valuestMin, "Min");
                    dataSet.setLineWidth(2);
                    dataSet.setColor(ContextCompat.getColor(context, com.mikepenz.materialize.R.color.md_blue_50));
                    dataSet.setDrawCircles(false);
                    dataSet.setMode(smoothMode());
                    dataSet.setFillAlpha(255);
                    dataSet.setFillColor(R.color.white);
                    dataSet.setDrawFilled(true);
                    entries.add(dataSet);
                }
            }

            if ((addHumidity && !enableFilters) ||
                    (filterLabels != null && filterLabels.contains(((TextView) view.findViewById(R.id.legend_humidity)).getText().toString()))) {
                LineDataSet dataSet = new LineDataSet(valueshu, ((TextView) view.findViewById(R.id.legend_humidity)).getText().toString());
                dataSet.setColor(ContextCompat.getColor(context, R.color.material_orange_600));
                dataSet.setLineWidth(2);
                dataSet.setDrawCircles(false);
                dataSet.setMode(smoothMode());
                dataSet.setDrawFilled(true);
                dataSet.setFillAlpha(40);
                dataSet.setFillColor(ContextCompat.getColor(context, R.color.material_orange_600));
                dataSet.setAxisDependency(YAxis.AxisDependency.RIGHT);
                entries.add(dataSet);
            }

            if ((addBarometer && !enableFilters) ||
                    (filterLabels != null && filterLabels.contains(((TextView) view.findViewById(R.id.legend_barometer)).getText().toString()))) {
                LineDataSet dataSet = new LineDataSet(valuesba, ((TextView) view.findViewById(R.id.legend_barometer)).getText().toString());
                dataSet.setColor(ContextCompat.getColor(context, R.color.material_green_600));
                dataSet.setLineWidth(2);
                dataSet.setDrawCircles(false);
                dataSet.setMode(smoothMode());
                dataSet.setAxisDependency(YAxis.AxisDependency.RIGHT);
                entries.add(dataSet);
            }

            if ((addCounter && !enableFilters) ||
                    (filterLabels != null && filterLabels.contains(((TextView) view.findViewById(R.id.legend_counter)).getText().toString()))) {
                LineDataSet dataSet = new LineDataSet(valuesc, ((TextView) view.findViewById(R.id.legend_counter)).getText().toString());
                dataSet.setColor(ContextCompat.getColor(context, R.color.material_indigo_600));
                dataSet.setLineWidth(2);
                dataSet.setDrawCircles(false);
                dataSet.setMode(LineDataSet.Mode.STEPPED);
                entries.add(dataSet);
            }

            if ((addPowerUsage && !enableFilters) ||
                    (filterLabels != null && filterLabels.contains(((TextView) view.findViewById(R.id.legend_powerusage)).getText().toString()))) {
                LineDataSet dataSet = new LineDataSet(valueeu, ((TextView) view.findViewById(R.id.legend_powerusage)).getText().toString());
                dataSet.setColor(ContextCompat.getColor(context, R.color.material_yellow_600));
                dataSet.setLineWidth(2);
                dataSet.setDrawCircles(false);
                // Power usage can spike sharply — LINEAR avoids misleading bezier waves
                dataSet.setMode(LineDataSet.Mode.LINEAR);
                entries.add(dataSet);
            }

            if ((addPowerDelivery && !enableFilters) ||
                    (filterLabels != null && filterLabels.contains(((TextView) view.findViewById(R.id.legend_powerdeliv)).getText().toString()))) {
                LineDataSet dataSet = new LineDataSet(valueeg, ((TextView) view.findViewById(R.id.legend_powerdeliv)).getText().toString());
                dataSet.setColor(ContextCompat.getColor(context, R.color.material_deep_purple_600));
                dataSet.setLineWidth(2);
                dataSet.setDrawCircles(false);
                // Power delivery can spike sharply — LINEAR avoids misleading bezier waves
                dataSet.setMode(LineDataSet.Mode.LINEAR);
                entries.add(dataSet);
            }

            if ((addPercentage && !enableFilters) ||
                    (filterLabels != null && filterLabels.contains(((TextView) view.findViewById(R.id.legend_percentage)).getText().toString()))) {
                LineDataSet dataSet = new LineDataSet(valuesv, ((TextView) view.findViewById(R.id.legend_percentage)).getText().toString());
                dataSet.setColor(ContextCompat.getColor(context, R.color.material_yellow_600));
                dataSet.setLineWidth(2);
                dataSet.setDrawCircles(false);
                dataSet.setMode(smoothMode());
                dataSet.setDrawFilled(true);
                dataSet.setFillAlpha(40);
                dataSet.setFillColor(ContextCompat.getColor(context, R.color.material_yellow_600));
                entries.add(dataSet);

                if (addPercentageRange) {
                    dataSet = new LineDataSet(valuesvMax, "Max");
                    dataSet.setLineWidth(2);
                    dataSet.setColor(ContextCompat.getColor(context, com.mikepenz.materialize.R.color.md_blue_50));
                    dataSet.setDrawCircles(false);
                    dataSet.setMode(smoothMode());
                    dataSet.setDrawFilled(true);
                    dataSet.setFillColor(com.mikepenz.materialize.R.color.md_blue_300);
                    entries.add(dataSet);

                    dataSet = new LineDataSet(valuesvMin, "Min");
                    dataSet.setLineWidth(2);
                    dataSet.setColor(ContextCompat.getColor(context, com.mikepenz.materialize.R.color.md_blue_50));
                    dataSet.setDrawCircles(false);
                    dataSet.setMode(smoothMode());
                    dataSet.setFillAlpha(255);
                    dataSet.setFillColor(R.color.white);
                    dataSet.setDrawFilled(true);
                    entries.add(dataSet);
                }
            }

            if ((addSecondPercentage && !enableFilters) ||
                    (filterLabels != null && filterLabels.contains(((TextView) view.findViewById(R.id.legend_percentage2)).getText().toString()))) {
                LineDataSet dataSet = new LineDataSet(valuesv2, ((TextView) view.findViewById(R.id.legend_percentage2)).getText().toString());
                dataSet.setColor(ContextCompat.getColor(context, R.color.material_orange_600));
                dataSet.setLineWidth(2);
                dataSet.setDrawCircles(false);
                dataSet.setMode(smoothMode()); // second percentage value
                entries.add(dataSet);
            }

            if ((addDirection && !enableFilters) ||
                    (filterLabels != null && filterLabels.contains(((TextView) view.findViewById(R.id.legend_direction)).getText().toString()))) {
                LineDataSet dataSet = new LineDataSet(valuesdi, ((TextView) view.findViewById(R.id.legend_direction)).getText().toString());
                dataSet.setColor(ContextCompat.getColor(context, R.color.material_green_600));
                dataSet.setLineWidth(2);
                dataSet.setDrawCircles(false);
                dataSet.setMode(LineDataSet.Mode.LINEAR); // direction can jump 0→360, bezier creates artefacts
                entries.add(dataSet);
            }

            if ((addSunPower && !enableFilters) ||
                    (filterLabels != null && filterLabels.contains(((TextView) view.findViewById(R.id.legend_sunpower)).getText().toString()))) {
                LineDataSet dataSet = new LineDataSet(valuesuv, ((TextView) view.findViewById(R.id.legend_sunpower)).getText().toString());
                dataSet.setColor(ContextCompat.getColor(context, R.color.material_deep_purple_600));
                dataSet.setLineWidth(2);
                dataSet.setDrawCircles(false);
                dataSet.setMode(smoothMode()); // nice bell curve for day range
                dataSet.setDrawFilled(true);
                dataSet.setFillAlpha(40);
                dataSet.setFillColor(ContextCompat.getColor(context, R.color.material_deep_purple_600));
                entries.add(dataSet);
            }

            if ((addSpeed && !enableFilters) ||
                    (filterLabels != null && filterLabels.contains(((TextView) view.findViewById(R.id.legend_speed)).getText().toString()))) {
                LineDataSet dataSet = new LineDataSet(valuessp, ((TextView) view.findViewById(R.id.legend_speed)).getText().toString());
                dataSet.setColor(ContextCompat.getColor(context, R.color.material_amber_600));
                dataSet.setLineWidth(2);
                dataSet.setDrawCircles(false);
                dataSet.setMode(LineDataSet.Mode.LINEAR); // wind speed is gusty/spiky, LINEAR is more honest
                entries.add(dataSet);
            }

            if ((addUsage && !enableFilters) ||
                    (filterLabels != null && filterLabels.contains(((TextView) view.findViewById(R.id.legend_usage)).getText().toString()))) {
                LineDataSet dataSet = new LineDataSet(valuesu, ((TextView) view.findViewById(R.id.legend_usage)).getText().toString());
                dataSet.setColor(ContextCompat.getColor(context, R.color.material_orange_600));
                dataSet.setLineWidth(2);
                dataSet.setDrawCircles(false);
                dataSet.setMode(LineDataSet.Mode.STEPPED);
                entries.add(dataSet);
            }

            if ((addRain && !enableFilters) ||
                    (filterLabels != null && filterLabels.contains(((TextView) view.findViewById(R.id.legend_rain)).getText().toString()))) {
                LineDataSet dataSet = new LineDataSet(valuesmm, ((TextView) view.findViewById(R.id.legend_rain)).getText().toString());
                dataSet.setColor(ContextCompat.getColor(context, R.color.material_light_green_600));
                dataSet.setLineWidth(2);
                dataSet.setDrawCircles(false);
                dataSet.setMode(LineDataSet.Mode.STEPPED);
                entries.add(dataSet);
            }

            if ((addCO2 && !enableFilters) ||
                    (filterLabels != null && filterLabels.contains(((TextView) view.findViewById(R.id.legend_co2)).getText().toString()))) {
                LineDataSet dataSet = new LineDataSet(valuesco2, ((TextView) view.findViewById(R.id.legend_co2)).getText().toString());
                dataSet.setColor(ContextCompat.getColor(context, R.color.material_blue_600));
                dataSet.setLineWidth(2);
                dataSet.setDrawCircles(false);
                dataSet.setMode(smoothMode()); // CO2 changes gradually
                dataSet.setDrawFilled(true);
                dataSet.setFillAlpha(40);
                dataSet.setFillColor(ContextCompat.getColor(context, R.color.material_blue_600));
                entries.add(dataSet);
            }

            if ((addCO2Min && !enableFilters) ||
                    (filterLabels != null && filterLabels.contains(((TextView) view.findViewById(R.id.legend_co2min)).getText().toString()))) {
                LineDataSet dataSet = new LineDataSet(valuesco2min, ((TextView) view.findViewById(R.id.legend_co2min)).getText().toString());
                dataSet.setColor(ContextCompat.getColor(context, R.color.material_light_green_600));
                dataSet.setLineWidth(2);
                dataSet.setDrawCircles(false);
                dataSet.setMode(smoothMode());
                entries.add(dataSet);
            }

            if ((addCO2Max && !enableFilters) ||
                    (filterLabels != null && filterLabels.contains(((TextView) view.findViewById(R.id.legend_co2max)).getText().toString()))) {
                LineDataSet dataSet = new LineDataSet(valuesco2max, ((TextView) view.findViewById(R.id.legend_co2max)).getText().toString());
                dataSet.setColor(ContextCompat.getColor(context, com.mikepenz.materialize.R.color.md_red_400));
                dataSet.setLineWidth(2);
                dataSet.setDrawCircles(false);
                dataSet.setMode(smoothMode());
                entries.add(dataSet);
            }

            if ((addLux && !enableFilters) ||
                    (filterLabels != null && filterLabels.contains(((TextView) view.findViewById(R.id.legend_Lux)).getText().toString()))) {
                LineDataSet dataSet = new LineDataSet(valuesLux, ((TextView) view.findViewById(R.id.legend_Lux)).getText().toString());
                dataSet.setColor(ContextCompat.getColor(context, R.color.material_blue_600));
                dataSet.setLineWidth(2);
                dataSet.setDrawCircles(false);
                dataSet.setMode(smoothMode()); // lux can look nice with bezier on day range
                dataSet.setDrawFilled(true);
                dataSet.setFillAlpha(40);
                dataSet.setFillColor(ContextCompat.getColor(context, R.color.material_blue_600));
                entries.add(dataSet);
            }

            if ((addLuxMin && !enableFilters) ||
                    (filterLabels != null && filterLabels.contains(((TextView) view.findViewById(R.id.legend_Luxmin)).getText().toString()))) {
                LineDataSet dataSet = new LineDataSet(valuesLuxmin, ((TextView) view.findViewById(R.id.legend_Luxmin)).getText().toString());
                dataSet.setColor(ContextCompat.getColor(context, R.color.material_light_green_600));
                dataSet.setLineWidth(2);
                dataSet.setDrawCircles(false);
                dataSet.setMode(smoothMode());
                entries.add(dataSet);
            }

            if ((addLuxMax && !enableFilters) ||
                    (filterLabels != null && filterLabels.contains(((TextView) view.findViewById(R.id.legend_Luxmax)).getText().toString()))) {
                LineDataSet dataSet = new LineDataSet(valuesLuxmax, ((TextView) view.findViewById(R.id.legend_Luxmax)).getText().toString());
                dataSet.setColor(ContextCompat.getColor(context, com.mikepenz.materialize.R.color.md_red_400));
                dataSet.setLineWidth(2);
                dataSet.setDrawCircles(false);
                dataSet.setMode(smoothMode());
                entries.add(dataSet);
            }

            if ((addLuxAvg && !enableFilters) ||
                    (filterLabels != null && filterLabels.contains(((TextView) view.findViewById(R.id.legend_LuxAvg)).getText().toString()))) {
                LineDataSet dataSet = new LineDataSet(valuesLuxAvg, ((TextView) view.findViewById(R.id.legend_LuxAvg)).getText().toString());
                dataSet.setColor(ContextCompat.getColor(context, com.mikepenz.materialize.R.color.md_yellow_400));
                dataSet.setLineWidth(2);
                dataSet.setDrawCircles(false);
                dataSet.setMode(smoothMode());
                entries.add(dataSet);
            }

            if (entries.size() > 1) {
                if (addTemperature) {
                    (view.findViewById(R.id.legend_temperature)).setVisibility(View.VISIBLE);
                    if (addSetpoint) {
                        (view.findViewById(R.id.legend_set_point)).setVisibility(View.VISIBLE);
                    }
                    addLabelFilters((String) ((TextView) view.findViewById(R.id.legend_temperature)).getText());
                }
                if (addHumidity) {
                    addLabelFilters((String) ((TextView) view.findViewById(R.id.legend_humidity)).getText());
                    (view.findViewById(R.id.legend_humidity)).setVisibility(View.VISIBLE);
                }
                if (addBarometer) {
                    (view.findViewById(R.id.legend_barometer)).setVisibility(View.VISIBLE);
                    addLabelFilters((String) ((TextView) view.findViewById(R.id.legend_barometer)).getText());
                }
                if (addCounter) {
                    (view.findViewById(R.id.legend_counter)).setVisibility(View.VISIBLE);
                    ((TextView) view.findViewById(R.id.legend_counter)).setText(axisYLabel);
                    addLabelFilters((String) ((TextView) view.findViewById(R.id.legend_counter)).getText());
                }
                if (addPercentage) {
                    (view.findViewById(R.id.legend_percentage)).setVisibility(View.VISIBLE);
                    addLabelFilters((String) ((TextView) view.findViewById(R.id.legend_percentage)).getText());
                }
                if (addSecondPercentage) {
                    (view.findViewById(R.id.legend_percentage2)).setVisibility(View.VISIBLE);
                    addLabelFilters((String) ((TextView) view.findViewById(R.id.legend_percentage2)).getText());
                }
                if (addDirection) {
                    (view.findViewById(R.id.legend_direction)).setVisibility(View.VISIBLE);
                    addLabelFilters((String) ((TextView) view.findViewById(R.id.legend_direction)).getText());
                }
                if (addSunPower) {
                    (view.findViewById(R.id.legend_sunpower)).setVisibility(View.VISIBLE);
                    addLabelFilters((String) ((TextView) view.findViewById(R.id.legend_sunpower)).getText());
                }
                if (addSpeed) {
                    (view.findViewById(R.id.legend_speed)).setVisibility(View.VISIBLE);
                    addLabelFilters((String) ((TextView) view.findViewById(R.id.legend_speed)).getText());
                }
                if (addUsage) {
                    (view.findViewById(R.id.legend_usage)).setVisibility(View.VISIBLE);
                    addLabelFilters((String) ((TextView) view.findViewById(R.id.legend_usage)).getText());
                }
                if (addPowerDelivery) {
                    (view.findViewById(R.id.legend_powerdeliv)).setVisibility(View.VISIBLE);
                    addLabelFilters((String) ((TextView) view.findViewById(R.id.legend_powerdeliv)).getText());
                }
                if (addPowerUsage) {
                    (view.findViewById(R.id.legend_powerusage)).setVisibility(View.VISIBLE);
                    addLabelFilters((String) ((TextView) view.findViewById(R.id.legend_powerusage)).getText());
                }
                if (addRain) {
                    (view.findViewById(R.id.legend_rain)).setVisibility(View.VISIBLE);
                    addLabelFilters((String) ((TextView) view.findViewById(R.id.legend_rain)).getText());
                }
                if (addCO2) {
                    (view.findViewById(R.id.legend_co2)).setVisibility(View.VISIBLE);
                    addLabelFilters((String) ((TextView) view.findViewById(R.id.legend_co2)).getText());
                }
                if (addCO2Min) {
                    (view.findViewById(R.id.legend_co2min)).setVisibility(View.VISIBLE);
                    addLabelFilters((String) ((TextView) view.findViewById(R.id.legend_co2min)).getText());
                }
                if (addCO2Max) {
                    (view.findViewById(R.id.legend_co2max)).setVisibility(View.VISIBLE);
                    addLabelFilters((String) ((TextView) view.findViewById(R.id.legend_co2max)).getText());
                }
                if (addLux) {
                    (view.findViewById(R.id.legend_Lux)).setVisibility(View.VISIBLE);
                    addLabelFilters((String) ((TextView) view.findViewById(R.id.legend_Lux)).getText());
                }
                if (addLuxMin) {
                    (view.findViewById(R.id.legend_Luxmin)).setVisibility(View.VISIBLE);
                    addLabelFilters((String) ((TextView) view.findViewById(R.id.legend_Luxmin)).getText());
                }
                if (addLuxMax) {
                    (view.findViewById(R.id.legend_Luxmax)).setVisibility(View.VISIBLE);
                    addLabelFilters((String) ((TextView) view.findViewById(R.id.legend_Luxmax)).getText());
                }
                if (addLuxAvg) {
                    (view.findViewById(R.id.legend_LuxAvg)).setVisibility(View.VISIBLE);
                    addLabelFilters((String) ((TextView) view.findViewById(R.id.legend_LuxAvg)).getText());
                }
            }

            ArrayList<ILineDataSet> dataSets = new ArrayList<>();
            dataSets.addAll(entries);

            if (mCompareGraphList != null && !mCompareGraphList.isEmpty() && compareEnabled) {
                List<Entry> cValuest = new ArrayList<>();
                List<Entry> cValueshu = new ArrayList<>();
                List<Entry> cValuesc = new ArrayList<>();
                List<Entry> cValuesv = new ArrayList<>();
                List<Entry> cValuesuv = new ArrayList<>();
                List<Entry> cValuesmm = new ArrayList<>();
                List<Entry> cValuesu = new ArrayList<>();
                List<Entry> cValuesCO2 = new ArrayList<>();
                List<Entry> cValuesLux = new ArrayList<>();
                Calendar cMydate = Calendar.getInstance();
                for (GraphPointInfo g : mCompareGraphList) {
                    try {
                        try {
                            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
                            cMydate.setTime(sdf.parse(g.getDateTime()));
                        } catch (ParseException e) {
                            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
                            cMydate.setTime(sdf.parse(g.getDateTime()));
                        }
                        if (!Float.isNaN(g.getTemperature()))
                            cValuest.add(new Entry(cMydate.getTimeInMillis(), g.getTemperature()));
                        if (isValidGraphValue(g.getHumidity())) {
                            try {
                                cValueshu.add(new Entry(cMydate.getTimeInMillis(), Integer.parseInt(g.getHumidity())));
                            } catch (Exception ex) {
                                try {
                                    cValueshu.add(new Entry(cMydate.getTimeInMillis(), Float.parseFloat(g.getHumidity())));
                                } catch (Exception ignored) {
                                }
                            }
                        }
                        if (isValidGraphValue(g.getCounter())) {
                            try {
                                cValuesc.add(new Entry(cMydate.getTimeInMillis(), Float.parseFloat(g.getCounter())));
                            } catch (Exception ignored) {
                            }
                        }
                        if (isValidGraphValue(g.getValue())) {
                            try {
                                cValuesv.add(new Entry(cMydate.getTimeInMillis(), Float.parseFloat(g.getValue())));
                            } catch (Exception ignored) {
                            }
                        }
                        if (isValidGraphValue(g.getSunPower())) {
                            try {
                                cValuesuv.add(new Entry(cMydate.getTimeInMillis(), Float.parseFloat(g.getSunPower())));
                            } catch (Exception ignored) {
                            }
                        }
                        if (isValidGraphValue(g.getRain())) {
                            try {
                                cValuesmm.add(new Entry(cMydate.getTimeInMillis(), Float.parseFloat(g.getRain())));
                            } catch (Exception ignored) {
                            }
                        }
                        if (isValidGraphValue(g.getUsage())) {
                            try {
                                cValuesu.add(new Entry(cMydate.getTimeInMillis(), Float.parseFloat(g.getUsage())));
                            } catch (Exception ignored) {
                            }
                        }
                        if (isValidGraphValue(g.getCo2())) {
                            try {
                                cValuesCO2.add(new Entry(cMydate.getTimeInMillis(), Float.parseFloat(g.getCo2())));
                            } catch (Exception ignored) {
                            }
                        }
                        if (isValidGraphValue(g.getLux())) {
                            try {
                                cValuesLux.add(new Entry(cMydate.getTimeInMillis(), Float.parseFloat(g.getLux())));
                            } catch (Exception ignored) {
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
                if (addTemperature && !cValuest.isEmpty()) {
                    int baseColor = ContextCompat.getColor(context, R.color.material_blue_600);
                    LineDataSet ds = new LineDataSet(cValuest, "Prev Temp");
                    ds.setColor(Color.argb(120, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)));
                    ds.setLineWidth(1.5f);
                    ds.setDrawCircles(false);
                    ds.setMode(smoothMode());
                    dataSets.add(ds);
                }
                if (addHumidity && !cValueshu.isEmpty()) {
                    int baseColor = ContextCompat.getColor(context, R.color.material_orange_600);
                    LineDataSet ds = new LineDataSet(cValueshu, "Prev Hum");
                    ds.setColor(Color.argb(120, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)));
                    ds.setLineWidth(1.5f);
                    ds.setDrawCircles(false);
                    ds.setMode(smoothMode());
                    ds.setAxisDependency(YAxis.AxisDependency.RIGHT);
                    dataSets.add(ds);
                }
                if (addCounter && !cValuesc.isEmpty()) {
                    int baseColor = ContextCompat.getColor(context, R.color.material_indigo_600);
                    LineDataSet ds = new LineDataSet(cValuesc, "Prev Counter");
                    ds.setColor(Color.argb(120, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)));
                    ds.setLineWidth(1.5f);
                    ds.setDrawCircles(false);
                    ds.setMode(LineDataSet.Mode.STEPPED);
                    dataSets.add(ds);
                }
                if (addPercentage && !cValuesv.isEmpty()) {
                    int baseColor = ContextCompat.getColor(context, R.color.material_yellow_600);
                    LineDataSet ds = new LineDataSet(cValuesv, "Prev Value");
                    ds.setColor(Color.argb(120, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)));
                    ds.setLineWidth(1.5f);
                    ds.setDrawCircles(false);
                    ds.setMode(smoothMode());
                    dataSets.add(ds);
                }
                if (addSunPower && !cValuesuv.isEmpty()) {
                    int baseColor = ContextCompat.getColor(context, R.color.material_deep_purple_600);
                    LineDataSet ds = new LineDataSet(cValuesuv, "Prev Sun");
                    ds.setColor(Color.argb(120, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)));
                    ds.setLineWidth(1.5f);
                    ds.setDrawCircles(false);
                    ds.setMode(smoothMode());
                    dataSets.add(ds);
                }
                if (addRain && !cValuesmm.isEmpty()) {
                    int baseColor = ContextCompat.getColor(context, R.color.material_light_green_600);
                    LineDataSet ds = new LineDataSet(cValuesmm, "Prev Rain");
                    ds.setColor(Color.argb(120, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)));
                    ds.setLineWidth(1.5f);
                    ds.setDrawCircles(false);
                    ds.setMode(LineDataSet.Mode.STEPPED);
                    dataSets.add(ds);
                }
                if (addUsage && !cValuesu.isEmpty()) {
                    int baseColor = ContextCompat.getColor(context, R.color.material_orange_600);
                    LineDataSet ds = new LineDataSet(cValuesu, "Prev Usage");
                    ds.setColor(Color.argb(120, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)));
                    ds.setLineWidth(1.5f);
                    ds.setDrawCircles(false);
                    ds.setMode(LineDataSet.Mode.STEPPED);
                    dataSets.add(ds);
                }
                if (addCO2 && !cValuesCO2.isEmpty()) {
                    int baseColor = ContextCompat.getColor(context, R.color.material_blue_600);
                    LineDataSet ds = new LineDataSet(cValuesCO2, "Prev CO2");
                    ds.setColor(Color.argb(120, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)));
                    ds.setLineWidth(1.5f);
                    ds.setDrawCircles(false);
                    ds.setMode(smoothMode());
                    dataSets.add(ds);
                }
                if (addLux && !cValuesLux.isEmpty()) {
                    int baseColor = ContextCompat.getColor(context, R.color.material_blue_600);
                    LineDataSet ds = new LineDataSet(cValuesLux, "Prev Lux");
                    ds.setColor(Color.argb(120, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)));
                    ds.setLineWidth(1.5f);
                    ds.setDrawCircles(false);
                    ds.setMode(smoothMode());
                    dataSets.add(ds);
                }
            }

            LineData lineChartData = new LineData(dataSets);
            lineChartData.setHighlightEnabled(true);
            lineChartData.setDrawValues(false);

            return lineChartData;
        } catch (Exception ex) {
            return null;
        }
    }

    private void addLabelFilters(String label) {
        if (!enableFilters) {
            if (!UsefulBits.isEmpty(label)) {
                if (lineLabels == null)
                    lineLabels = new ArrayList<>();
                if (!lineLabels.contains(label))
                    lineLabels.add(label);
            }
        }
    }

    private void updateStats() {
        if (mGraphList == null || mGraphList.isEmpty() || statsBar == null) return;
        List<Float> values = new ArrayList<>();
        String unit;
        switch (type) {
            case "temp":
                unit = "°";
                for (GraphPointInfo g : mGraphList) {
                    if (!Float.isNaN(g.getTemperature())) values.add(g.getTemperature());
                }
                break;
            case "Percentage":
                unit = "%";
                for (GraphPointInfo g : mGraphList) {
                    try {
                        if (isValidGraphValue(g.getValue())) values.add(Float.parseFloat(g.getValue()));
                    } catch (Exception ignored) {
                    }
                }
                break;
            case "counter":
                unit = " kWh";
                for (GraphPointInfo g : mGraphList) {
                    try {
                        if (isValidGraphValue(g.getCounter())) values.add(Float.parseFloat(g.getCounter()));
                    } catch (Exception ignored) {
                    }
                }
                break;
            case "rain":
                unit = " mm";
                for (GraphPointInfo g : mGraphList) {
                    try {
                        if (isValidGraphValue(g.getRain())) values.add(Float.parseFloat(g.getRain()));
                    } catch (Exception ignored) {
                    }
                }
                break;
            case "wind":
                unit = " m/s";
                for (GraphPointInfo g : mGraphList) {
                    try {
                        if (isValidGraphValue(g.getSpeed())) values.add(Float.parseFloat(g.getSpeed()));
                    } catch (Exception ignored) {
                    }
                }
                break;
            default:
                unit = "";
                for (GraphPointInfo g : mGraphList) {
                    try {
                        if (isValidGraphValue(g.getValue())) {
                            values.add(Float.parseFloat(g.getValue()));
                            continue;
                        }
                    } catch (Exception ignored) {
                    }
                    if (!Float.isNaN(g.getTemperature())) values.add(g.getTemperature());
                }
                break;
        }
        if (values.isEmpty()) {
            statsBar.setVisibility(View.GONE);
            return;
        }
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE, sum = 0;
        for (float v : values) {
            if (v < min) min = v;
            if (v > max) max = v;
            sum += v;
        }
        float avg = sum / values.size();
        statMin.setText(String.format(Locale.getDefault(), "Min: %.1f%s", min, unit));
        statAvg.setText(String.format(Locale.getDefault(), "Avg: %.1f%s", avg, unit));
        statMax.setText(String.format(Locale.getDefault(), "Max: %.1f%s", max, unit));
        statsBar.setVisibility(View.VISIBLE);
    }

    private void loadThreshold() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        currentThreshold = prefs.getFloat("threshold_" + idx, 0f);
    }

    private void saveThreshold(float value) {
        currentThreshold = value;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putFloat("threshold_" + idx, value).apply();
    }

    private void applyThresholdToChart() {
        if (chart == null) return;
        chart.getAxisLeft().removeAllLimitLines();
        if (currentThreshold != 0f) {
            LimitLine ll = new LimitLine(currentThreshold, String.format(Locale.getDefault(), "%.1f", currentThreshold));
            ll.setLineWidth(1.5f);
            ll.setLineColor(Color.RED);
            ll.setLabelPosition(LimitLine.LimitLabelPosition.RIGHT_TOP);
            ll.setTextSize(10f);
            ll.setTextColor(Color.RED);
            ll.enableDashedLine(10f, 5f, 0f);
            chart.getAxisLeft().addLimitLine(ll);
            chart.getAxisLeft().setDrawLimitLinesBehindData(true);
        }
        chart.invalidate();
    }

    private void getCompareGraphs() {
        String compareDate = getCompareDate();
        new Thread(() -> StaticHelper.getDomoticz(context).getGraphDataForDate(idx, range, type, compareDate, new GraphDataReceiver() {
            @Override
            public void onReceive(ArrayList<GraphPointInfo> grphPoints) {
                mCompareGraphList = grphPoints;
                LineData columnData = generateData(root);
                if (columnData != null && getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        chart.setData(columnData);
                        chart.invalidate();
                        chart.setVisibility(View.VISIBLE);
                        applyThresholdToChart();
                    });
                }
            }

            @Override
            public void onError(Exception ex) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() ->
                            Toast.makeText(context, "No comparison data available", Toast.LENGTH_SHORT).show());
                }
                compareEnabled = false;
                mCompareGraphList = null;
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> compareChip.setChecked(false));
                }
            }
        })).start();
    }

    private String getCompareDate() {
        Calendar cal = Calendar.getInstance();
        switch (range) {
            case "week":
                cal.add(Calendar.WEEK_OF_YEAR, -1);
                break;
            case "month":
                cal.add(Calendar.MONTH, -1);
                break;
            case "year":
                cal.add(Calendar.YEAR, -1);
                break;
            default:
                cal.add(Calendar.DAY_OF_YEAR, -1);
                break;
        }
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.getTime());
    }

    private void shareChart() {
        try {
            Bitmap bitmap = chart.getChartBitmap();
            File cacheDir = new File(context.getCacheDir(), "graph_images");
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
            File imageFile = new File(cacheDir, "graph_" + idx + ".png");
            FileOutputStream fos = new FileOutputStream(imageFile);
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos);
            fos.flush();
            fos.close();

            Uri contentUri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".graphprovider", imageFile);

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("image/png");
            shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, axisYLabel + " - " + range);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, getString(R.string.action_share)));
        } catch (Exception e) {
            Log.e(TAG, "Error sharing chart", e);
            Toast.makeText(context, "Could not share chart", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_graph_sort, menu);
        MenuItem filterItem = menu.findItem(R.id.action_sort);
        if (filterItem != null) {
            filterItem.setVisible(lineLabels != null && lineLabels.size() > 1);
        }
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_sort) {
            String[] items = new String[lineLabels.size()];
            lineLabels.toArray(items);

            new MaterialDialog.Builder(context)
                    .title(context.getString(R.string.filter))
                    .items(items)
                    .itemsCallbackMultiChoice(selectedFilters, (dialog, which, text) -> {
                        selectedFilters = which;
                        enableFilters = true;

                        if (text != null && text.length > 0) {
                            filterLabels = new ArrayList<>();
                            for (CharSequence c : text)
                                filterLabels.add((String) c);

                            LineData columnData = generateData(root);
                            if (columnData != null) {
                                chart.setData(columnData);
                                chart.invalidate();
                                chart.setVisibility(View.VISIBLE);
                                chart.animateX(1000);
                                if (getActivity() != null)
                                    getActivity().invalidateOptionsMenu();
                            }
                        } else {
                            enableFilters = false;
                            Toast.makeText(context, context.getString(R.string.filter_graph_empty), Toast.LENGTH_SHORT).show();
                        }
                        return true;
                    })
                    .positiveText(R.string.ok)
                    .negativeText(R.string.cancel)
                    .show();
            return true;
        }
        /*
        else if (item.getItemId() == R.id.action_threshold) {
            EditText input = new EditText(context);
            input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                    | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
            if (currentThreshold != 0f) {
                input.setText(String.format(Locale.getDefault(), "%.1f", currentThreshold));
            }
            input.setHint(getString(R.string.threshold_hint));
            new AlertDialog.Builder(context)
                    .setTitle(getString(R.string.set_threshold))
                    .setView(input)
                    .setPositiveButton(R.string.ok, (dialog, which) -> {
                        try {
                            String val = input.getText().toString().trim();
                            float threshold = val.isEmpty() ? 0f : Float.parseFloat(val);
                            saveThreshold(threshold);
                            applyThresholdToChart();
                        } catch (NumberFormatException e) {
                            Toast.makeText(context, "Invalid value", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNeutralButton(R.string.clear_threshold, (dialog, which) -> {
                        saveThreshold(0f);
                        applyThresholdToChart();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return true;
        } else if (item.getItemId() == R.id.action_share) {
            shareChart();
            return true;
        }*/
        return false;
    }
}
