package com.masheeds.player;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.TextView;

import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

final class MasheedsDialog extends Dialog {
    private final LinearLayout body;
    private final LinearLayout actions;
    private final int ink;
    private final int muted;
    private final int accent;
    private final int onAccent;
    private final int background;
    private final int accentSurface;
    private final ColorStateList rippleColor;
    private final ColorStateList indicatorTint;

    MasheedsDialog(Context context, CharSequence title) {
        super(context);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setCanceledOnTouchOutside(true);

        ink = context.getColor(R.color.text_primary);
        muted = context.getColor(R.color.text_secondary);
        accent = context.getColor(R.color.accent);
        onAccent = context.getColor(R.color.on_accent);
        background = context.getColor(R.color.app_background);
        accentSurface = context.getColor(R.color.accent_surface);
        rippleColor = ColorStateList.valueOf(context.getColor(R.color.ripple));
        indicatorTint = new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[0]},
                new int[]{accent, muted});

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(16));
        card.setLayoutDirection(context.getResources().getConfiguration().getLayoutDirection());
        GradientDrawable panel = rounded(context.getColor(R.color.surface), 26);
        panel.setStroke(dp(1), context.getColor(R.color.seek_track));
        card.setBackground(panel);

        TextView heading = new TextView(context);
        heading.setText(title);
        heading.setTextSize(22);
        heading.setTextColor(ink);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setMaxLines(2);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        if (Build.VERSION.SDK_INT >= 28) {
            card.setAccessibilityPaneTitle(title);
            heading.setAccessibilityHeading(true);
        }
        card.addView(heading, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bodySize = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        bodySize.topMargin = dp(6);
        card.addView(body, bodySize);

        actions = new LinearLayout(context);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsSize = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        actionsSize.topMargin = dp(16);
        card.addView(actions, actionsSize);
        setContentView(card);

        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.48f);
        window.setGravity(Gravity.CENTER);
    }

    @Override public void show() {
        super.show();
        int screenWidth = getContext().getResources().getConfiguration().screenWidthDp;
        getWindow().setLayout(dp(Math.min(420, screenWidth - 32)), WRAP_CONTENT);
    }

    void addChoice(CharSequence label, boolean selected, Runnable action) {
        RadioButton choice = new RadioButton(getContext());
        styleChoice(choice);
        choice.setText(label);
        choice.setChecked(selected);
        choice.setOnClickListener(view -> action.run());
        addToBody(choice, WRAP_CONTENT);
    }

    void addChecklist(CharSequence[] labels, boolean[] checked) {
        ListView list = new ListView(getContext());
        list.setDivider(new ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dp(6));
        list.setItemsCanFocus(true);
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return labels.length; }
            @Override public CharSequence getItem(int position) { return labels[position]; }
            @Override public long getItemId(int position) { return position; }

            @Override public View getView(int position, View recycled, ViewGroup parent) {
                CheckBox item = (CheckBox) recycled;
                if (item == null) {
                    item = new CheckBox(getContext());
                    styleChoice(item);
                }
                item.setOnCheckedChangeListener(null);
                item.setText(getItem(position));
                item.setChecked(checked[position]);
                item.setOnCheckedChangeListener((view, selected) -> checked[position] = selected);
                return item;
            }
        });
        addList(list, labels.length);
    }

    void addOptions(CharSequence[] labels, IntConsumer onSelection) {
        ListView list = new ListView(getContext());
        list.setDivider(new ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dp(6));
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return labels.length; }
            @Override public CharSequence getItem(int position) { return labels[position]; }
            @Override public long getItemId(int position) { return position; }

            @Override public View getView(int position, View recycled, ViewGroup parent) {
                TextView item = (TextView) recycled;
                if (item == null) {
                    item = new TextView(getContext());
                    item.setTextSize(16);
                    item.setTextColor(ink);
                    item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                    item.setMinHeight(dp(52));
                    item.setPaddingRelative(dp(14), dp(8), dp(14), dp(8));
                    item.setBackground(rounded(background, 14));
                }
                item.setText(getItem(position));
                return item;
            }
        });
        list.setOnItemClickListener((parent, view, position, id) -> onSelection.accept(position));
        addList(list, labels.length);
    }

    BaseAdapter addRadioOptions(CharSequence[] labels, IntPredicate selected, IntConsumer onSelection) {
        ListView list = new ListView(getContext());
        list.setDivider(new ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dp(6));
        list.setItemsCanFocus(true);
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return labels.length; }
            @Override public CharSequence getItem(int position) { return labels[position]; }
            @Override public long getItemId(int position) { return position; }

            @Override public View getView(int position, View recycled, ViewGroup parent) {
                RadioButton item = (RadioButton) recycled;
                if (item == null) {
                    item = new RadioButton(getContext());
                    styleChoice(item);
                }
                item.setText(getItem(position));
                item.setChecked(selected.test(position));
                item.setOnClickListener(view -> onSelection.accept(position));
                return item;
            }
        };
        list.setAdapter(adapter);
        addList(list, labels.length);
        return adapter;
    }

    void addList(ListView list, int rowCount) {
        int screenHeight = getContext().getResources().getConfiguration().screenHeightDp;
        int maxHeight = Math.min(288, screenHeight / 2);
        int listHeight = Math.min(rowCount, 5) * 58 - 6;
        addToBody(list, dp(Math.min(listHeight, maxHeight)));
    }

    EditText addInput(int hint, CharSequence initialText) {
        EditText input = new EditText(getContext());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setTextColor(ink);
        input.setHintTextColor(muted);
        input.setHint(hint);
        input.setText(initialText);
        input.setSelectAllOnFocus(true);
        input.setMinHeight(dp(52));
        input.setPaddingRelative(dp(14), dp(8), dp(14), dp(8));
        input.setBackground(rounded(background, 14));
        addToBody(input, WRAP_CONTENT);
        input.requestFocus();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        return input;
    }

    TextView addMessage(CharSequence message) {
        TextView text = new TextView(getContext());
        text.setText(message);
        text.setTextSize(15);
        text.setTextColor(muted);
        addToBody(text, WRAP_CONTENT);
        return text;
    }

    Button addMenuItem(int label, Runnable action) {
        Button item = actionButton(label, ink, background, action);
        item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        addToBody(item, WRAP_CONTENT);
        return item;
    }

    void addCancel() {
        addAction(android.R.string.cancel, false, this::dismiss);
    }

    Button addAction(int label, boolean primary, Runnable action) {
        Button button = actionButton(label, primary ? onAccent : accent, primary ? accent : Color.TRANSPARENT, action);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        if (actions.getChildCount() > 0) {
            size.setMarginStart(dp(8));
        }
        actions.addView(button, size);
        return button;
    }

    private Button actionButton(int label, int textColor, int fill, Runnable action) {
        Button button = new Button(getContext(), null, android.R.attr.borderlessButtonStyle);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(textColor);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(48));
        button.setPaddingRelative(dp(14), dp(7), dp(14), dp(7));
        button.setBackground(new RippleDrawable(rippleColor, rounded(fill, 14), rounded(Color.WHITE, 14)));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void styleChoice(CompoundButton choice) {
        choice.setTextColor(ink);
        choice.setTextSize(16);
        choice.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        choice.setMinHeight(dp(52));
        choice.setPaddingRelative(dp(10), dp(8), dp(12), dp(8));
        choice.setButtonTintList(indicatorTint);
        StateListDrawable fill = new StateListDrawable();
        fill.addState(new int[]{android.R.attr.state_checked}, rounded(accentSurface, 14));
        fill.addState(new int[0], rounded(background, 14));
        choice.setBackground(new RippleDrawable(rippleColor, fill, rounded(Color.WHITE, 14)));
    }

    private void addToBody(View view, int height) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(MATCH_PARENT, height);
        size.topMargin = dp(8);
        body.addView(view, size);
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }
}
