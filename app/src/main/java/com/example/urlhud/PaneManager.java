package com.example.urlhud;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Owns the tree of split/leaf panes and renders it with nested LinearLayouts +
 * draggable divider Views. This is a direct, native-Android port of the
 * split/leaf node tree that index.html builds out of flexbox <div>s in the
 * Electron version — same shape, same operations (init / splitPane /
 * closePane), just real Views instead of DOM nodes since Android WebView has
 * no equivalent of Electron's <webview> tag to nest inside HTML.
 */
public class PaneManager {

    /** Creates a fully configured, ready-to-use WebView for a new leaf pane. */
    public interface WebViewFactory {
        WebView createWebView(String url);
    }

    private static final float MIN_PERCENT = 10f;
    private static final float MAX_PERCENT = 90f;

    private abstract static class Node {
        SplitNode parent;
    }

    private static class LeafNode extends Node {
        WebView webView;
    }

    private static class SplitNode extends Node {
        String direction; // "row" or "column" - matches Electron's split-container class
        float ratio = 50f;
        Node first;
        Node second;
        LinearLayout container;
        FrameLayout firstWrap;
        FrameLayout secondWrap;
    }

    private final Context context;
    private final FrameLayout rootSlot;
    private final WebViewFactory factory;
    private final int dividerThicknessPx;

    private Node rootNode;

    public PaneManager(Context context, FrameLayout rootSlot, WebViewFactory factory) {
        this.context = context;
        this.rootSlot = rootSlot;
        this.factory = factory;
        this.dividerThicknessPx = Math.round(6 * context.getResources().getDisplayMetrics().density);
    }

    /** Creates the very first pane. Mirrors window.splitAPI.onInit in index.html. */
    public WebView init(String url) {
        LeafNode leaf = new LeafNode();
        leaf.webView = factory.createWebView(url);
        rootNode = leaf;
        rootSlot.removeAllViews();
        rootSlot.addView(leaf.webView, matchParent());
        return leaf.webView;
    }

    /**
     * Rebuilds the whole split/leaf tree from a JSON blob previously produced
     * by serialize() - this is what lets MainActivity reopen the exact same
     * split layout, with each pane's last-opened URL restored, instead of
     * always starting fresh from START_URL. Returns the first leaf's WebView
     * so the caller has something valid to use as the initial target pane.
     */
    public WebView restore(JSONObject treeJson) throws JSONException {
        rootNode = buildNode(treeJson, null);
        rootSlot.removeAllViews();
        rootSlot.addView(currentViewOf(rootNode), matchParent());
        return firstLeafWebView(rootNode);
    }

    /** Serializes the current tree to JSON: split direction/ratio, or a leaf's URL. */
    public JSONObject serialize() {
        if (rootNode == null) return null;
        try {
            return serializeNode(rootNode);
        } catch (JSONException e) {
            return null;
        }
    }

    private Node buildNode(JSONObject json, SplitNode parent) throws JSONException {
        if ("split".equals(json.optString("type"))) {
            SplitNode node = new SplitNode();
            node.parent = parent;
            node.direction = json.getString("direction");
            node.ratio = (float) json.optDouble("ratio", 50.0);
            boolean isRow = "row".equals(node.direction);

            LinearLayout container = new LinearLayout(context);
            container.setOrientation(isRow ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            FrameLayout firstWrap = new FrameLayout(context);
            FrameLayout secondWrap = new FrameLayout(context);

            Node firstNode = buildNode(json.getJSONObject("first"), node);
            Node secondNode = buildNode(json.getJSONObject("second"), node);
            node.first = firstNode;
            node.second = secondNode;

            firstWrap.addView(currentViewOf(firstNode), matchParent());
            secondWrap.addView(currentViewOf(secondNode), matchParent());

            View divider = new View(context);
            divider.setBackgroundColor(0xFF1A1A1A);

            container.addView(firstWrap, weightedParams(isRow, node.ratio));
            container.addView(divider, dividerParams(isRow));
            container.addView(secondWrap, weightedParams(isRow, 100f - node.ratio));

            node.container = container;
            node.firstWrap = firstWrap;
            node.secondWrap = secondWrap;
            attachDividerDrag(divider, node);

            return node;
        } else {
            LeafNode leaf = new LeafNode();
            leaf.parent = parent;
            String url = json.optString("url", "");
            leaf.webView = factory.createWebView(url.isEmpty() ? "about:blank" : url);
            return leaf;
        }
    }

    private JSONObject serializeNode(Node node) throws JSONException {
        JSONObject obj = new JSONObject();
        if (node instanceof LeafNode) {
            WebView wv = ((LeafNode) node).webView;
            Object tag = wv.getTag();
            String url = (tag instanceof String) ? (String) tag : wv.getUrl();
            obj.put("type", "leaf");
            obj.put("url", url != null ? url : "");
        } else {
            SplitNode s = (SplitNode) node;
            obj.put("type", "split");
            obj.put("direction", s.direction);
            obj.put("ratio", s.ratio);
            obj.put("first", serializeNode(s.first));
            obj.put("second", serializeNode(s.second));
        }
        return obj;
    }

    private WebView firstLeafWebView(Node node) {
        return (node instanceof LeafNode) ? ((LeafNode) node).webView : firstLeafWebView(((SplitNode) node).first);
    }

    /** Splits whichever pane hosts `target`, matching splitPane() in index.html. */
    public void splitPane(WebView target, String direction) {
        if (target == null) return;
        LeafNode leaf = findLeaf(rootNode, target);
        if (leaf == null) return;

        SplitNode parent = leaf.parent;
        View existingView = leaf.webView;

        LeafNode newLeaf = new LeafNode();
        newLeaf.webView = factory.createWebView("about:blank");

        SplitNode splitNode = new SplitNode();
        splitNode.direction = direction;
        splitNode.first = leaf;
        splitNode.second = newLeaf;
        leaf.parent = splitNode;
        newLeaf.parent = splitNode;

        boolean isRow = "row".equals(direction);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(isRow ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);

        FrameLayout firstWrap = new FrameLayout(context);
        FrameLayout secondWrap = new FrameLayout(context);

        ViewGroup existingParent = (ViewGroup) existingView.getParent();
        if (existingParent != null) existingParent.removeView(existingView);
        firstWrap.addView(existingView, matchParent());
        secondWrap.addView(newLeaf.webView, matchParent());

        View divider = new View(context);
        divider.setBackgroundColor(0xFF1A1A1A);

        container.addView(firstWrap, weightedParams(isRow, 50f));
        container.addView(divider, dividerParams(isRow));
        container.addView(secondWrap, weightedParams(isRow, 50f));

        splitNode.container = container;
        splitNode.firstWrap = firstWrap;
        splitNode.secondWrap = secondWrap;

        attachDividerDrag(divider, splitNode);

        if (parent == null) {
            rootNode = splitNode;
            rootSlot.removeAllViews();
            rootSlot.addView(container, matchParent());
        } else {
            FrameLayout slot = (parent.first == leaf) ? parent.firstWrap : parent.secondWrap;
            if (parent.first == leaf) parent.first = splitNode; else parent.second = splitNode;
            splitNode.parent = parent;
            slot.removeAllViews();
            slot.addView(container, matchParent());
        }
    }

    /** Removes whichever pane hosts `target`, matching closePane() in index.html. */
    public void closePane(WebView target) {
        if (target == null) return;
        LeafNode leaf = findLeaf(rootNode, target);
        if (leaf == null) return;

        SplitNode parent = leaf.parent;
        if (parent == null) return; // this is the only pane - nothing to remove

        Node sibling = (parent.first == leaf) ? parent.second : parent.first;
        SplitNode grandparent = parent.parent;
        View siblingView = currentViewOf(sibling);

        // Destroy the closed pane's WebView so it stops running in the background.
        leaf.webView.destroy();

        ViewGroup siblingHolder = (ViewGroup) siblingView.getParent();
        if (siblingHolder != null) siblingHolder.removeView(siblingView);

        ViewGroup containerHolder = (ViewGroup) parent.container.getParent();
        if (containerHolder != null) containerHolder.removeView(parent.container);

        if (grandparent == null) {
            rootNode = sibling;
            sibling.parent = null;
            rootSlot.removeAllViews();
            rootSlot.addView(siblingView, matchParent());
        } else {
            FrameLayout slot = (grandparent.first == parent) ? grandparent.firstWrap : grandparent.secondWrap;
            if (grandparent.first == parent) grandparent.first = sibling; else grandparent.second = sibling;
            sibling.parent = grandparent;
            slot.removeAllViews();
            slot.addView(siblingView, matchParent());
        }
    }

    private LeafNode findLeaf(Node node, WebView target) {
        if (node == null) return null;
        if (node instanceof LeafNode) {
            LeafNode ln = (LeafNode) node;
            return (ln.webView == target) ? ln : null;
        }
        SplitNode s = (SplitNode) node;
        LeafNode found = findLeaf(s.first, target);
        return found != null ? found : findLeaf(s.second, target);
    }

    private View currentViewOf(Node node) {
        return (node instanceof LeafNode) ? ((LeafNode) node).webView : ((SplitNode) node).container;
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout.LayoutParams weightedParams(boolean isRow, float weight) {
        return isRow
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, weight);
    }

    private LinearLayout.LayoutParams dividerParams(boolean isRow) {
        return isRow
                ? new LinearLayout.LayoutParams(dividerThicknessPx, ViewGroup.LayoutParams.MATCH_PARENT, 0)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dividerThicknessPx, 0);
    }

    /** Drag-to-resize, matching attachResizerEvents() in index.html. */
    private void attachDividerDrag(View divider, SplitNode node) {
        final int[] loc = new int[2];
        divider.setOnTouchListener((v, event) -> {
            boolean isRow = "row".equals(node.direction);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setBackgroundColor(0xFF4A90E2);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    node.container.getLocationOnScreen(loc);
                    float percent;
                    if (isRow) {
                        percent = ((event.getRawX() - loc[0]) / node.container.getWidth()) * 100f;
                    } else {
                        percent = ((event.getRawY() - loc[1]) / node.container.getHeight()) * 100f;
                    }
                    percent = Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, percent));
                    node.ratio = percent;

                    LinearLayout.LayoutParams fp = (LinearLayout.LayoutParams) node.firstWrap.getLayoutParams();
                    LinearLayout.LayoutParams sp = (LinearLayout.LayoutParams) node.secondWrap.getLayoutParams();
                    fp.weight = percent;
                    sp.weight = 100f - percent;
                    node.firstWrap.setLayoutParams(fp);
                    node.secondWrap.setLayoutParams(sp);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setBackgroundColor(0xFF1A1A1A);
                    return true;
                default:
                    return false;
            }
        });
    }
}
