# Artifact H-column readback

- Task: `a422272c-a993-4553-8c46-58a89e39c20b`
- Artifact: `bc0972fc-f860-4f2a-8903-72c889434a76.xlsx`
- Read-only source: the existing artifact under the Java application's artifact root; no task or database row was changed.
- Office-aware readback: cells `测试用例!H2:H9` resolve to empty shared strings.
- Raw OOXML: each cell stores `<v>33</v>` with type `s`; `33` is the shared-string table index, and index 33 resolves to the empty string. The H-column width is `35.0` and is not a cell value.
- Conclusion: the external artifact renderer displayed the shared-string index as `33`. This is not an Apache POI column-mapping defect, so this change deliberately leaves the Excel exporter unchanged.
