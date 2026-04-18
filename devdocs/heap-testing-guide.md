# Heap Feature Testing Guide

## Prerequisites

1. Build everything:
   ```
   gradlew.bat build
   gradlew.bat :agent:agentJar
   gradlew.bat :sample:jar
   ```
2. Run the sample app: `gradlew.bat :sample:jar` then launch the JAR
3. Run Bytesight: `gradlew.bat :composeApp:run`
4. Attach to the sample app process from the Bytesight connection screen

---

## 1. Histogram Tab

### 1a. Capture Snapshot
- Click the **Heap** tab in the sidebar
- Click **Capture Snapshot**
- **Expected**: A table of class names appears with columns: Class, Instance Count, Total Size
- **Verify**: You should see `com.bugdigger.sample.models.User` (8+ instances), `com.bugdigger.sample.models.Product` (12+ instances), `com.bugdigger.sample.services.HeapDemoData$LinkedNode` (51 instances), `com.bugdigger.sample.services.HeapDemoData$TreeNode` (31+ instances), `com.bugdigger.sample.services.HeapDemoData$Container` (4 instances)
- Also verify `java.lang.String` shows a high instance count

### 1b. Sorting & Filtering
- Click column headers to sort by count or size
- Type a class name fragment in the filter field (e.g., `LinkedNode`)
- **Expected**: Only matching rows appear

### 1c. List Instances
- Click on `HeapDemoData$LinkedNode` row
- **Expected**: Instance list pane opens showing 51 instances with address, shallow size

### 1d. Object Graph
- Click on an instance from the list
- **Expected**: Object graph pane shows fields: `index` (int), `label` (String), `next` (LinkedNode reference or null)
- Click on the `next` field reference to navigate deeper into the linked list chain
- **Verify**: The graph expands showing the next node's fields

### 1e. Context Menu → Open in Inspector
- **Long-press** on a histogram row (e.g., `User`)
- **Expected**: Context menu appears with "Open in Inspector"
- Click it
- **Expected**: App navigates to Inspector tab with that class auto-selected in the class list

---

## 2. Search Tab

### 2a. String Value Search
- Switch to the **Search** tab
- Enter `hello world` in the search field and click **Search**
- **Expected**: Results show instances of `java.lang.String` containing "hello world" (at least 5 from HeapDemoData)
- Click on a class name in the results
- **Expected**: Navigates to Inspector for that class

### 2b. Search for Specific Values
- Search for `HIGHLY_DUPLICATED_CONFIG_KEY`
- **Expected**: ~20 String instances found
- Search for `/api/v1/users`
- **Expected**: ~12 String instances found
- Search for `shared-leaf`
- **Expected**: At least 1 String instance (from Container metadata)

### 2c. Search for Field Values
- Search for `alice` — should find String instances from User email/username fields
- Search for `Wireless Mouse` — should find String from Product name

---

## 3. Duplicates Tab

### 3a. Find Duplicate Strings
- Switch to the **Duplicates** tab
- Click **Find Duplicates**
- **Expected**: Table shows groups of strings with identical content, sorted by wasted bytes (count × size)
- **Verify these appear**:
  - `HIGHLY_DUPLICATED_CONFIG_KEY` — 20 copies
  - `/api/v1/users` — 12 copies
  - `hello world` — 5 copies
  - `error: connection refused` — 5 copies
  - `null pointer exception` — 5 copies
  - `bytesight` — 5 copies
  - `sample application` — 5 copies
  - `duplicate value` — 5 copies

### 3b. Inspect from Duplicates
- Click **Inspect String** button in a duplicates row header
- **Expected**: Navigates to Inspector tab with `java.lang.String` selected

---

## 4. Object Graph Deep Dive

### 4a. Binary Tree
- From Histogram, find `HeapDemoData$TreeNode` and list instances
- Select an instance, view object graph
- **Expected**: Fields include `name` (String), `value` (int), `left` (TreeNode or null), `right` (TreeNode or null)
- Expanding left/right references shows the tree structure

### 4b. Container with Shared References
- Find `HeapDemoData$Container`, list instances
- Select the `root` container (metadata has `type=root`)
- **Expected**: `children` list shows 2 Container references (child-A, child-B)
- Navigate into child-A → its `children` → shared-node
- Navigate into child-B → its `children` → shared-node
- **Verify**: Both reference the same object (same address)

### 4c. Instance Pane → Inspect Button
- When viewing instances of any class, click the **Inspect** button in the instance pane header
- **Expected**: Navigates to Inspector for that class

---

## 5. Header Text Color Fix

- Open the Heap tab
- **Verify**: The "Heap" title text is clearly visible (white/light text on dark background)
- Previously it was black and invisible against the dark theme

---

## 6. Edge Cases

- **Empty search**: Enter empty string in Search, click Search — should show error or no results
- **No snapshot**: Try switching to Search/Duplicates without capturing a snapshot first — verify graceful handling
- **Re-snapshot**: Capture a second snapshot — verify data refreshes
- **Large class**: Click on `java.lang.String` in histogram — may have thousands of instances; verify UI remains responsive
