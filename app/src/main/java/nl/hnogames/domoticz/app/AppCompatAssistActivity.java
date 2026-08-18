package nl.hnogames.domoticz.app;

import android.annotation.TargetApi;
import android.app.assist.AssistContent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.appbar.AppBarLayout;

import org.json.JSONException;
import org.json.JSONObject;

public class AppCompatAssistActivity extends AppCompatActivity {

    /**
     * System bars (status + navigation) and display cutout insets.
     */
    private static final int INSET_TYPES =
            WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Enable edge-to-edge display on all API levels: the system bars stay
        // transparent and this app is responsible for the insets.
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        // Apply window insets to the root view
        applyWindowInsets();
    }

    /**
     * Apply window insets for the edge-to-edge display.
     *
     * Two supported layout shapes:
     * <ol>
     * <li>CoordinatorLayout + AppBarLayout (main screen, settings, logs, ...):
     * the app bar draws edge-to-edge under the status bar and pads itself for
     * it (fitsSystemWindows); the CoordinatorLayout is padded on the left,
     * right and bottom so the scrolling content, snackbars and anchored views
     * stay above the navigation bar.</li>
     * <li>Standalone Toolbar without an AppBarLayout (update, temp graphs, ...):
     * the toolbar pads itself for the status bar and the layout root is padded
     * on the left, right and bottom.</li>
     * </ol>
     * Insets are never consumed on the padded host, so the AppBarLayout (or
     * the toolbar) still receives the top inset.
     */
    protected void applyWindowInsets() {
        View content = findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup) || ((ViewGroup) content).getChildCount() == 0) {
            return;
        }
        View layoutRoot = ((ViewGroup) content).getChildAt(0);

        AppBarLayout appBar = findFirstView(layoutRoot, AppBarLayout.class);
        if (appBar != null) {
            // The app bar extends under the status bar and pads itself for it.
            appBar.setFitsSystemWindows(true);

            // The rest of the content must avoid the left/right/bottom system
            // bars. Prefer the CoordinatorLayout as inset host so snackbars and
            // anchored views inside it stay above the navigation bar too.
            View insetHost = findFirstView(layoutRoot, CoordinatorLayout.class);
            if (insetHost == null) {
                insetHost = layoutRoot;
            }
            if (insetHost instanceof ViewGroup) {
                // Do not rely on the XML attribute; the listener below owns the
                // left/right/bottom padding (top stays 0, the app bar handles it).
                insetHost.setFitsSystemWindows(false);
                ViewCompat.setOnApplyWindowInsetsListener(insetHost, (v, insets) -> {
                    Insets i = insets.getInsets(INSET_TYPES);
                    v.setPadding(i.left, v.getPaddingTop(), i.right, i.bottom);
                    // Not consumed: the AppBarLayout still needs the top inset.
                    return insets;
                });
            }
        } else {
            // No app bar: a standalone toolbar draws under the status bar.
            Toolbar toolbar = findFirstView(layoutRoot, Toolbar.class);
            if (toolbar != null) {
                ViewCompat.setOnApplyWindowInsetsListener(toolbar, (v, insets) -> {
                    Insets i = insets.getInsets(INSET_TYPES);
                    v.setPadding(v.getPaddingLeft(), i.top,
                            v.getPaddingRight(), v.getPaddingBottom());
                    // Not consumed: the layout root still needs the insets.
                    return insets;
                });
            }

            // The rest of the screen avoids the left/right/bottom system bars.
            if (layoutRoot instanceof ViewGroup) {
                layoutRoot.setFitsSystemWindows(false);
                ViewCompat.setOnApplyWindowInsetsListener(layoutRoot, (v, insets) -> {
                    Insets i = insets.getInsets(INSET_TYPES);
                    v.setPadding(i.left, v.getPaddingTop(), i.right, i.bottom);
                    return insets;
                });
            }
        }
    }

    /**
     * Depth-first search for the first view of the given type (inclusive of
     * the starting view).
     */
    private <T extends View> T findFirstView(View view, Class<T> type) {
        if (type.isInstance(view)) {
            return type.cast(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = findFirstView(group.getChildAt(i), type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Override
    @TargetApi(Build.VERSION_CODES.M)
    public void onProvideAssistContent(AssistContent outContent) {
        super.onProvideAssistContent(outContent);
        try {
            outContent.setStructuredData(
                    new JSONObject()
                            .put("@type", "SoftwareApplication")
                            .put("author", "Domoticz")
                            .put("name", "Domoticz")
                            .put("id", "http://www.domoticz.com")
                            .put("description", "Domoticz is a very light weight home automation system that lets you monitor and configure miscellaneous devices, including lights, switches, various sensors/meters like temperature, rainfall, wind, ultraviolet (UV) radiation, electricity usage/production, gas consumption and many more."
                            ).toString()
            );
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }
}
