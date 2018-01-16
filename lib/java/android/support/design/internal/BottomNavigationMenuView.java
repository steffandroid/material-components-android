/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.support.design.internal;

import static android.support.annotation.RestrictTo.Scope.LIBRARY_GROUP;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.support.annotation.Nullable;
import android.support.annotation.RestrictTo;
import android.support.annotation.StyleRes;
import android.support.design.bottomnavigation.LabelVisibilityMode;
import android.support.design.bottomnavigation.ShiftingMode;
import android.support.transition.AutoTransition;
import android.support.transition.TransitionManager;
import android.support.transition.TransitionSet;
import android.support.v4.util.Pools;
import android.support.v4.view.ViewCompat;
import android.support.v4.view.animation.FastOutSlowInInterpolator;
import android.support.v7.content.res.AppCompatResources;
import android.support.v7.view.menu.MenuBuilder;
import android.support.v7.view.menu.MenuItemImpl;
import android.support.v7.view.menu.MenuView;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

/** @hide For internal use only. */
@RestrictTo(LIBRARY_GROUP)
public class BottomNavigationMenuView extends ViewGroup implements MenuView {
  private static final long ACTIVE_ANIMATION_DURATION_MS = 115L;

  private static final int[] CHECKED_STATE_SET = {android.R.attr.state_checked};
  private static final int[] DISABLED_STATE_SET = {-android.R.attr.state_enabled};

  private final TransitionSet set;
  private final int inactiveItemMaxWidth;
  private final int inactiveItemMinWidth;
  private final int activeItemMaxWidth;
  private final int activeItemMinWidth;
  private final int itemHeight;
  private final OnClickListener onClickListener;
  private final Pools.Pool<BottomNavigationItemView> itemPool = new Pools.SynchronizedPool<>(5);

  private int shiftingModeFlag = ShiftingMode.SHIFTING_MODE_AUTO;
  private boolean itemHorizontalTranslation;
  @LabelVisibilityMode private int labelVisibilityMode;

  private BottomNavigationItemView[] buttons;
  private int selectedItemId = 0;
  private int selectedItemPosition = 0;
  private ColorStateList itemIconTint;
  private ColorStateList itemTextColorFromUser;
  private final ColorStateList itemTextColorDefault;
  @StyleRes private int itemTextAppearanceInactive;
  @StyleRes private int itemTextAppearanceActive;
  private int itemBackgroundRes;
  private int[] tempChildWidths;

  private BottomNavigationPresenter presenter;
  private MenuBuilder menu;

  public BottomNavigationMenuView(Context context) {
    this(context, null);
  }

  public BottomNavigationMenuView(Context context, AttributeSet attrs) {
    super(context, attrs);
    final Resources res = getResources();
    inactiveItemMaxWidth =
        res.getDimensionPixelSize(R.dimen.design_bottom_navigation_item_max_width);
    inactiveItemMinWidth =
        res.getDimensionPixelSize(R.dimen.design_bottom_navigation_item_min_width);
    activeItemMaxWidth =
        res.getDimensionPixelSize(R.dimen.design_bottom_navigation_active_item_max_width);
    activeItemMinWidth =
        res.getDimensionPixelSize(R.dimen.design_bottom_navigation_active_item_min_width);
    itemHeight = res.getDimensionPixelSize(R.dimen.design_bottom_navigation_height);
    itemTextColorDefault = createDefaultColorStateList(android.R.attr.textColorSecondary);

    set = new AutoTransition();
    set.setOrdering(TransitionSet.ORDERING_TOGETHER);
    set.setDuration(ACTIVE_ANIMATION_DURATION_MS);
    set.setInterpolator(new FastOutSlowInInterpolator());
    set.addTransition(new TextScale());

    onClickListener =
        new OnClickListener() {
          @Override
          public void onClick(View v) {
            final BottomNavigationItemView itemView = (BottomNavigationItemView) v;
            MenuItem item = itemView.getItemData();
            if (!menu.performItemAction(item, presenter, 0)) {
              item.setChecked(true);
            }
          }
        };
    tempChildWidths = new int[BottomNavigationMenu.MAX_ITEM_COUNT];
  }

  @Override
  public void initialize(MenuBuilder menu) {
    this.menu = menu;
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    final int width = MeasureSpec.getSize(widthMeasureSpec);
    // Use visible item count to calculate widths
    final int visibleCount = menu.getVisibleItems().size();
    // Use total item counts to measure children
    final int totalCount = getChildCount();

    final int heightSpec = MeasureSpec.makeMeasureSpec(itemHeight, MeasureSpec.EXACTLY);

    if (isShifting(shiftingModeFlag, visibleCount) && itemHorizontalTranslation) {
      final View activeChild = getChildAt(selectedItemPosition);
      int activeItemWidth = activeItemMinWidth;
      if (activeChild.getVisibility() != View.GONE) {
        // Do an AT_MOST measure pass on the active child to get its desired width, and resize the
        // active child view based on that width
        activeChild.measure(
            MeasureSpec.makeMeasureSpec(activeItemMaxWidth, MeasureSpec.AT_MOST), heightSpec);
        activeItemWidth = Math.max(activeItemWidth, activeChild.getMeasuredWidth());
      }
      final int inactiveCount = visibleCount - (activeChild.getVisibility() != View.GONE ? 1 : 0);
      final int activeMaxAvailable = width - inactiveCount * inactiveItemMinWidth;
      final int activeWidth =
          Math.min(activeMaxAvailable, Math.min(activeItemWidth, activeItemMaxWidth));
      final int inactiveMaxAvailable =
          (width - activeWidth) / (inactiveCount == 0 ? 1 : inactiveCount);
      final int inactiveWidth = Math.min(inactiveMaxAvailable, inactiveItemMaxWidth);
      int extra = width - activeWidth - inactiveWidth * inactiveCount;

      for (int i = 0; i < totalCount; i++) {
        if (getChildAt(i).getVisibility() != View.GONE) {
          tempChildWidths[i] = (i == selectedItemPosition) ? activeWidth : inactiveWidth;
          // Account for integer division which sometimes leaves some extra pixel spaces.
          // e.g. If the nav was 10px wide, and 3 children were measured to be 3px-3px-3px, there
          // would be a 1px gap somewhere, which this fills in.
          if (extra > 0) {
            tempChildWidths[i]++;
            extra--;
          }
        } else {
          tempChildWidths[i] = 0;
        }
      }
    } else {
      final int maxAvailable = width / (visibleCount == 0 ? 1 : visibleCount);
      final int childWidth = Math.min(maxAvailable, activeItemMaxWidth);
      int extra = width - childWidth * visibleCount;
      for (int i = 0; i < totalCount; i++) {
        if (getChildAt(i).getVisibility() != View.GONE) {
          tempChildWidths[i] = childWidth;
          if (extra > 0) {
            tempChildWidths[i]++;
            extra--;
          }
        } else {
          tempChildWidths[i] = 0;
        }
      }
    }

    int totalWidth = 0;
    for (int i = 0; i < totalCount; i++) {
      final View child = getChildAt(i);
      if (child.getVisibility() == GONE) {
        continue;
      }
      child.measure(
          MeasureSpec.makeMeasureSpec(tempChildWidths[i], MeasureSpec.EXACTLY), heightSpec);
      ViewGroup.LayoutParams params = child.getLayoutParams();
      params.width = child.getMeasuredWidth();
      totalWidth += child.getMeasuredWidth();
    }
    setMeasuredDimension(
        View.resolveSizeAndState(
            totalWidth, MeasureSpec.makeMeasureSpec(totalWidth, MeasureSpec.EXACTLY), 0),
        View.resolveSizeAndState(itemHeight, heightSpec, 0));
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    final int count = getChildCount();
    final int width = right - left;
    final int height = bottom - top;
    int used = 0;
    for (int i = 0; i < count; i++) {
      final View child = getChildAt(i);
      if (child.getVisibility() == GONE) {
        continue;
      }
      if (ViewCompat.getLayoutDirection(this) == ViewCompat.LAYOUT_DIRECTION_RTL) {
        child.layout(width - used - child.getMeasuredWidth(), 0, width - used, height);
      } else {
        child.layout(used, 0, child.getMeasuredWidth() + used, height);
      }
      used += child.getMeasuredWidth();
    }
  }

  @Override
  public int getWindowAnimations() {
    return 0;
  }

  /**
   * Sets the tint which is applied to the menu items' icons.
   *
   * @param tint the tint to apply
   */
  public void setIconTintList(ColorStateList tint) {
    itemIconTint = tint;
    if (buttons != null) {
      for (BottomNavigationItemView item : buttons) {
        item.setIconTintList(tint);
      }
    }
  }

  /**
   * Returns the tint which is applied for the menu item labels.
   *
   * @return the ColorStateList that is used to tint menu items' icons
   */
  @Nullable
  public ColorStateList getIconTintList() {
    return itemIconTint;
  }

  /**
   * Sets the text color to be used for the menu item labels.
   *
   * @param color the ColorStateList used for menu item labels
   */
  public void setItemTextColor(ColorStateList color) {
    itemTextColorFromUser = color;
    if (buttons != null) {
      for (BottomNavigationItemView item : buttons) {
        item.setTextColor(color);
      }
    }
  }

  /**
   * Returns the text color used for menu item labels.
   *
   * @return the ColorStateList used for menu items labels
   */
  public ColorStateList getItemTextColor() {
    return itemTextColorFromUser;
  }

  /**
   * Sets the text appearance to be used for inactive menu item labels.
   *
   * @param textAppearanceRes the text appearance ID used for inactive menu item labels
   */
  public void setItemTextAppearanceInactive(@StyleRes int textAppearanceRes) {
    this.itemTextAppearanceInactive = textAppearanceRes;
    if (buttons != null) {
      for (BottomNavigationItemView item : buttons) {
        item.setTextAppearanceInactive(textAppearanceRes);
        // Set the text color if the user has set it, since itemTextColorFromUser takes precedence
        // over a color set in the text appearance.
        if (itemTextColorFromUser != null) {
          item.setTextColor(itemTextColorFromUser);
        }
      }
    }
  }

  /**
   * Returns the text appearance used for inactive menu item labels.
   *
   * @return the text appearance ID used for inactive menu item labels
   */
  @StyleRes
  public int getItemTextAppearanceInactive() {
    return itemTextAppearanceInactive;
  }

  /**
   * Sets the text appearance to be used for the active menu item label.
   *
   * @param textAppearanceRes the text appearance ID used for the active menu item label
   */
  public void setItemTextAppearanceActive(@StyleRes int textAppearanceRes) {
    this.itemTextAppearanceActive = textAppearanceRes;
    if (buttons != null) {
      for (BottomNavigationItemView item : buttons) {
        item.setTextAppearanceActive(textAppearanceRes);
        // Set the text color if the user has set it, since itemTextColorFromUser takes precedence
        // over a color set in the text appearance.
        if (itemTextColorFromUser != null) {
          item.setTextColor(itemTextColorFromUser);
        }
      }
    }
  }

  /**
   * Returns the text appearance used for the active menu item label.
   *
   * @return the text appearance ID used for the active menu item label
   */
  @StyleRes
  public int getItemTextAppearanceActive() {
    return itemTextAppearanceActive;
  }

  /**
   * Sets the resource ID to be used for item backgrounds.
   *
   * @param background the resource ID of the background
   */
  public void setItemBackgroundRes(int background) {
    itemBackgroundRes = background;
    if (buttons != null) {
      for (BottomNavigationItemView item : buttons) {
        item.setItemBackground(background);
      }
    }
  }

  /**
   * Returns the resource ID for the background of the menu items.
   *
   * @return the resource ID for the background
   */
  public int getItemBackgroundRes() {
    return itemBackgroundRes;
  }

  /**
   * Sets shifting mode override flag for menu view. If this flag is set to {@link
   * ShiftingMode#SHIFTING_MODE_OFF}, this menu will not have shifting behavior even if it has more
   * than 3 children. If this flag is set to {@link ShiftingMode#SHIFTING_MODE_ON}, this menu will
   * have shifting behavior for any number of children. If this flag is set to {@link
   * ShiftingMode#SHIFTING_MODE_AUTO} this menu will have shifting behavior only if it has 3 or more
   * children.
   *
   * @param shiftingMode one of {@link ShiftingMode#SHIFTING_MODE_OFF}, {@link
   *     ShiftingMode#SHIFTING_MODE_ON}, or {@link ShiftingMode#SHIFTING_MODE_AUTO}
   * @deprecated use {@link #setLabelVisibilityMode(int)} instead
   */
  @Deprecated
  public void setShiftingMode(@ShiftingMode int shiftingMode) {
    shiftingModeFlag = shiftingMode;
  }

  /**
   * Gets the status of shifting mode override flag for this menu view.
   *
   * @return Shifting mode flag for this menu view (default {@link ShiftingMode#SHIFTING_MODE_AUTO})
   * @deprecated use {@link #getLabelVisibilityMode()} instead
   */
  @Deprecated
  @ShiftingMode
  public int getShiftingMode() {
    return shiftingModeFlag;
  }

  /**
   * Sets the navigation items' label visibility mode.
   *
   * <p>The label is either always shown, never shown, or only shown when activated. Also supports
   * legacy mode, which uses {@link ShiftingMode} to decide whether the label should be shown.
   *
   * @param labelVisibilityMode mode which decides whether or not the label should be shown. Can be
   *     one of {@link LabelVisibilityMode#LABEL_VISIBILITY_LEGACY}, {@link
   *     LabelVisibilityMode#LABEL_VISIBILITY_SELECTED}, {@link
   *     LabelVisibilityMode#LABEL_VISIBILITY_LABELED}, or {@link
   *     LabelVisibilityMode#LABEL_VISIBILITY_UNLABELED}
   * @see #getLabelVisibilityMode()
   */
  public void setLabelVisibilityMode(@LabelVisibilityMode int labelVisibilityMode) {
    this.labelVisibilityMode = labelVisibilityMode;
  }

  /**
   * Returns the current label visibility mode.
   *
   * @see #setLabelVisibilityMode(int)
   */
  public int getLabelVisibilityMode() {
    return labelVisibilityMode;
  }

  /**
   * Sets whether the menu items horizontally translate in shifting mode.
   *
   * @param itemHorizontalTranslation whether the menu items horizontally translate in shifting mode
   * @see #getItemHorizontalTranslation()
   */
  public void setItemHorizontalTranslation(boolean itemHorizontalTranslation) {
    this.itemHorizontalTranslation = itemHorizontalTranslation;
  }

  /**
   * Returns whether the menu items horizontally translate in shifting mode.
   *
   * @return whether the menu items horizontally translate in shifting mode
   * @see #setItemHorizontalTranslation(boolean)
   */
  public boolean getItemHorizontalTranslation() {
    return itemHorizontalTranslation;
  }

  public ColorStateList createDefaultColorStateList(int baseColorThemeAttr) {
    final TypedValue value = new TypedValue();
    if (!getContext().getTheme().resolveAttribute(baseColorThemeAttr, value, true)) {
      return null;
    }
    ColorStateList baseColor = AppCompatResources.getColorStateList(getContext(), value.resourceId);
    if (!getContext()
        .getTheme()
        .resolveAttribute(android.support.v7.appcompat.R.attr.colorPrimary, value, true)) {
      return null;
    }
    int colorPrimary = value.data;
    int defaultColor = baseColor.getDefaultColor();
    return new ColorStateList(
        new int[][] {DISABLED_STATE_SET, CHECKED_STATE_SET, EMPTY_STATE_SET},
        new int[] {
          baseColor.getColorForState(DISABLED_STATE_SET, defaultColor), colorPrimary, defaultColor
        });
  }

  public void setPresenter(BottomNavigationPresenter presenter) {
    this.presenter = presenter;
  }

  public void buildMenuView() {
    removeAllViews();
    if (buttons != null) {
      for (BottomNavigationItemView item : buttons) {
        if (item != null) {
          itemPool.release(item);
        }
      }
    }
    if (menu.size() == 0) {
      selectedItemId = 0;
      selectedItemPosition = 0;
      buttons = null;
      return;
    }
    buttons = new BottomNavigationItemView[menu.size()];
    boolean shifting = isShifting(shiftingModeFlag, menu.getVisibleItems().size());
    for (int i = 0; i < menu.size(); i++) {
      presenter.setUpdateSuspended(true);
      menu.getItem(i).setCheckable(true);
      presenter.setUpdateSuspended(false);
      BottomNavigationItemView child = getNewItem();
      buttons[i] = child;
      child.setIconTintList(itemIconTint);
      // Set the text color the default, then look for another text color in order of precedence.
      child.setTextColor(itemTextColorDefault);
      child.setTextAppearanceInactive(itemTextAppearanceInactive);
      child.setTextAppearanceActive(itemTextAppearanceActive);
      child.setTextColor(itemTextColorFromUser);
      child.setItemBackground(itemBackgroundRes);
      child.setShiftingMode(shifting);
      child.setLabelVisibilityMode(labelVisibilityMode);
      child.initialize((MenuItemImpl) menu.getItem(i), 0);
      child.setItemPosition(i);
      child.setOnClickListener(onClickListener);
      addView(child);
    }
    selectedItemPosition = Math.min(menu.size() - 1, selectedItemPosition);
    menu.getItem(selectedItemPosition).setChecked(true);
  }

  public void updateMenuView() {
    if (menu == null || buttons == null) {
      return;
    }

    final int menuSize = menu.size();
    if (menuSize != buttons.length) {
      // The size has changed. Rebuild menu view from scratch.
      buildMenuView();
      return;
    }

    int previousSelectedId = selectedItemId;

    for (int i = 0; i < menuSize; i++) {
      MenuItem item = menu.getItem(i);
      if (item.isChecked()) {
        selectedItemId = item.getItemId();
        selectedItemPosition = i;
      }
    }
    if (previousSelectedId != selectedItemId) {
      // Note: this has to be called before BottomNavigationItemView#initialize().
      TransitionManager.beginDelayedTransition(this, set);
    }

    boolean shifting = isShifting(shiftingModeFlag, menu.getVisibleItems().size());
    for (int i = 0; i < menuSize; i++) {
      presenter.setUpdateSuspended(true);
      buttons[i].setShiftingMode(shifting);
      buttons[i].setLabelVisibilityMode(labelVisibilityMode);
      buttons[i].initialize((MenuItemImpl) menu.getItem(i), 0);
      presenter.setUpdateSuspended(false);
    }
  }

  private BottomNavigationItemView getNewItem() {
    BottomNavigationItemView item = itemPool.acquire();
    if (item == null) {
      item = new BottomNavigationItemView(getContext());
    }
    return item;
  }

  public int getSelectedItemId() {
    return selectedItemId;
  }

  private boolean isShifting(@ShiftingMode int shiftingMode, int childCount) {
    return shiftingMode == ShiftingMode.SHIFTING_MODE_AUTO
        ? childCount > 3
        : shiftingMode == ShiftingMode.SHIFTING_MODE_ON;
  }

  void tryRestoreSelectedItemId(int itemId) {
    final int size = menu.size();
    for (int i = 0; i < size; i++) {
      MenuItem item = menu.getItem(i);
      if (itemId == item.getItemId()) {
        selectedItemId = itemId;
        selectedItemPosition = i;
        item.setChecked(true);
        break;
      }
    }
  }
}
