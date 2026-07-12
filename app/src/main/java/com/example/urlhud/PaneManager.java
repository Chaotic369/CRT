package com.example.urlhud;

import android.content.Context;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Owns the binary split-pane tree of WebViews and keeps the on-screen
 * LinearLayout hierarchy in sync with it. This is the native equivalent of
 * the recursive renderLeaf()/renderSplit() layout logic in index.html,
 * rebuilt with real Android views instead of DOM nodes since each pane is
 * a genuine top-level WebView (see MainActivity's class doc for why).
 *
 * Tree shape:
 *   Node.leaf(webView)                       -- a single pane
 *   Node.split(direction, first, second)     -- two children, 50/50 weight
 *
 * "row"  -> children side by side (horizontal LinearLayout)
 * "col"  -> children stacked (vertical LinearLayout)
 */
public class PaneManager {

    public interface WebViewFactory {
        WebView create(String url);
    }

    private static final String DEFAULT_SPLIT_URL = "https://example.com";

    private static class Node {
        boolean leaf;
        WebView webView;      // set when leaf
        String direction;     // "row" | "col", set when split
        Node first;
        Node second;
        Node parent;

        static Node newLeaf(WebView wv) {
            Node n = new Node();
            n.leaf = true;
            n.webView = wv;
            return n;
        }

        static Node newSplit(String direction, Node first, Node second) {
            Node n = new Node();
            n.leaf = false;
            n.direction = direction;
            n.first = first;
            n.second = second;
            first.parent = n;
            second.parent = n;
            return n;
        }
    }

    private final Context context;
    private final FrameLayout container;
    private final WebViewFactory factory;
    private Node root;
    private WebView activePane;

    public PaneManager(Context context, FrameLayout container, WebViewFactory factory) {
        this.context = context;
        this.container = container;
        this.factory = factory;
    }

    /** Creates a fresh single-pane tree and renders it. Returns the new WebView. */
    public WebView init(String url) {
        WebView wv = factory.create(url);
        root = Node.newLeaf(wv);
        render();
        return wv;
    }

    /** Rebuilds the tree (and views) from a serialized JSON tree. Returns a leaf WebView to make active, or null on failure. */
    public WebView restore(JSONObject tree) {
        try {
            root = buildFromJson(tree);
        } catch (JSONException e) {
            root = null;
            return null;
        }
        if (root == null) return null;
        render();
        return firstLeaf(root);
    }

    private Node buildFromJson(JSONObject o) throws JSONException {
        String type = o.optString("type", "leaf");
        if ("split".equals(type)) {
            String direction = o.optString("direction", "row");
            Node first = buildFromJson(o.getJSONObject("first"));
            Node second = buildFromJson(o.getJSONObject("second"));
            if (first == null || second == null) return null;
            return Node.newSplit(direction, first, second);
        } else {
            String url = o.optString("url", DEFAULT_SPLIT_URL);
            WebView wv = factory.create(url);
            return Node.newLeaf(wv);
        }
    }

    /** Splits the pane containing `pane` into two, adding a fresh pane alongside it. Returns the new WebView, or null if `pane` isn't found. */
    public WebView splitPane(WebView pane, String direction) {
        Node leaf = findLeaf(root, pane);
        if (leaf == null) return null;

        WebView newWebView = factory.create(DEFAULT_SPLIT_URL);
        Node newLeaf = Node.newLeaf(newWebView);
        Node originalLeaf = Node.newLeaf(pane);
        Node split = Node.newSplit("col".equals(direction) ? "col" : "row", originalLeaf, newLeaf);

        if (leaf.parent == null) {
            root = split;
        } else {
            Node parent = leaf.parent;
            if (parent.first == leaf) parent.first = split; else parent.second = split;
            split.parent = parent;
        }

        render();
        return newWebView;
    }

    /** Removes the pane containing `pane`, collapsing its parent split. Returns the WebView that should become active, or null if there's nothing left to close. */
    public WebView closePane(WebView pane) {
        Node leaf = findLeaf(root, pane);
        if (leaf == null || leaf.parent == null) return null; // can't close the last remaining pane

        Node parent = leaf.parent;
        Node sibling = (parent.first == leaf) ? parent.second : parent.first;
        sibling.parent = parent.parent;

        if (parent.parent == null) {
            root = sibling;
        } else {
            Node grandparent = parent.parent;
            if (grandparent.first == parent) grandparent.first = sibling; else grandparent.second = sibling;
        }

        pane.destroy();
        render();
        return firstLeaf(sibling);
    }

    public void setActivePane(WebView pane) {
        this.activePane = pane;
    }

    public boolean hasSplit() {
        return root != null && !root.leaf;
    }

    /** Serializes the current tree, storing each leaf's last-known URL via its View tag. */
    public JSONObject serialize() {
        if (root == null) return null;
        try {
            return serializeNode(root);
        } catch (JSONException e) {
            return null;
        }
    }

    private JSONObject serializeNode(Node node) throws JSONException {
        JSONObject o = new JSONObject();
        if (node.leaf) {
            o.put("type", "leaf");
            Object tag = node.webView.getTag();
            o.put("url", tag != null ? tag.toString() : DEFAULT_SPLIT_URL);
        } else {
            o.put("type", "split");
            o.put("direction", node.direction);
            o.put("first", serializeNode(node.first));
            o.put("second", serializeNode(node.second));
        }
        return o;
    }

    private Node findLeaf(Node node, WebView pane) {
        if (node == null) return null;
        if (node.leaf) return node.webView == pane ? node : null;
        Node found = findLeaf(node.first, pane);
        return found != null ? found : findLeaf(node.second, pane);
    }

    private WebView firstLeaf(Node node) {
        if (node == null) return null;
        return node.leaf ? node.webView : firstLeaf(node.first);
    }

    /** Rebuilds the on-screen view hierarchy under `container` to match the current tree. */
    private void render() {
        container.removeAllViews();
        if (root == null) return;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        container.addView(buildView(root), lp);
    }

    private android.view.View buildView(Node node) {
        if (node.leaf) {
            detachFromParent(node.webView);
            return node.webView;
        }

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation("col".equals(node.direction) ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        layout.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        android.view.View firstView = buildView(node.first);
        android.view.View secondView = buildView(node.second);

        boolean horizontal = layout.getOrientation() == LinearLayout.HORIZONTAL;
        LinearLayout.LayoutParams firstLp = horizontal
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        LinearLayout.LayoutParams secondLp = horizontal
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);

        layout.addView(firstView, firstLp);
        layout.addView(secondView, secondLp);
        return layout;
    }

    private void detachFromParent(android.view.View view) {
        if (view == null) return;
        ViewGroup parent = (ViewGroup) view.getParent();
        if (parent != null) parent.removeView(view);
    }
}
