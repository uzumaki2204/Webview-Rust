use serde::Serialize;

/// Current state of a download task.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum DownloadStatus {
    Pending,
    InProgress,
    Completed,
    Failed,
}

/// A single tracked download.
#[derive(Debug, Clone, Serialize)]
pub struct DownloadTask {
    pub id: u32,
    pub url: String,
    pub mime_type: String,
    pub file_name: String,
    pub status: DownloadStatus,
    /// Download progress in the range `[0.0, 1.0]`.
    pub progress: f32,
}

/// Tracks active and historical download tasks.
pub struct DownloadManager {
    tasks: Vec<DownloadTask>,
    next_id: u32,
}

impl DownloadManager {
    /// Create an empty `DownloadManager`.
    pub fn new() -> Self {
        DownloadManager {
            tasks: Vec::new(),
            next_id: 1,
        }
    }

    /// Enqueue a new download.  
    /// The filename is extracted from the last path segment of `url`; if the
    /// URL has no path segment a generic name is used instead.  
    /// Returns the new task's id.
    pub fn enqueue(&mut self, url: &str, mime_type: &str) -> u32 {
        let id = self.next_id;
        self.next_id += 1;

        let file_name = extract_filename(url, id);

        let task = DownloadTask {
            id,
            url: url.to_owned(),
            mime_type: mime_type.to_owned(),
            file_name,
            status: DownloadStatus::Pending,
            progress: 0.0,
        };

        self.tasks.push(task);
        id
    }

    /// Update the download progress for a task.  
    /// `progress` is clamped to `[0.0, 1.0]`.  
    /// The task status is set to `InProgress` if it was previously `Pending`.
    pub fn update_progress(&mut self, id: u32, progress: f32) {
        if let Some(task) = self.tasks.iter_mut().find(|t| t.id == id) {
            task.progress = progress.clamp(0.0, 1.0);
            if task.status == DownloadStatus::Pending {
                task.status = DownloadStatus::InProgress;
            }
        }
    }

    /// Mark a task as successfully completed (progress = 1.0).
    pub fn complete(&mut self, id: u32) {
        if let Some(task) = self.tasks.iter_mut().find(|t| t.id == id) {
            task.status = DownloadStatus::Completed;
            task.progress = 1.0;
        }
    }

    /// Mark a task as failed.
    pub fn fail(&mut self, id: u32) {
        if let Some(task) = self.tasks.iter_mut().find(|t| t.id == id) {
            task.status = DownloadStatus::Failed;
        }
    }

    /// Look up a task by id.
    pub fn get_task(&self, id: u32) -> Option<&DownloadTask> {
        self.tasks.iter().find(|t| t.id == id)
    }

    /// Return references to all tasks that are `Pending` or `InProgress`.
    pub fn active_downloads(&self) -> Vec<&DownloadTask> {
        self.tasks
            .iter()
            .filter(|t| {
                t.status == DownloadStatus::Pending || t.status == DownloadStatus::InProgress
            })
            .collect()
    }

    /// Return references to all tasks (completed, failed, and active).
    pub fn all_tasks(&self) -> &[DownloadTask] {
        &self.tasks
    }
}

impl Default for DownloadManager {
    fn default() -> Self {
        Self::new()
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/// Extract a human-readable filename from a URL.
///
/// Strips the query string / fragment and takes the last non-empty path
/// segment.  Falls back to `"download_<id>"` when the URL has no useful
/// path component.
fn extract_filename(url: &str, fallback_id: u32) -> String {
    // Remove the scheme (everything up to "://") for simpler parsing.
    let without_scheme = url
        .find("://")
        .map(|i| &url[i + 3..])
        .unwrap_or(url);

    // Host ends at the first '/' — ignore the domain name.
    let path_and_query = match without_scheme.find('/') {
        Some(i) => &without_scheme[i + 1..],
        None => "",
    };

    // Strip the query string and fragment.
    let path_part = path_and_query
        .find('?')
        .or_else(|| path_and_query.find('#'))
        .map(|i| &path_and_query[..i])
        .unwrap_or(path_and_query);

    // Take the last non-empty segment after '/'.
    path_part
        .split('/')
        .filter(|s| !s.is_empty())
        .last()
        .map(str::to_owned)
        .filter(|s| s.contains('.')) // prefer segments that look like file names
        .unwrap_or_else(|| format!("download_{}", fallback_id))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn enqueue_and_complete() {
        let mut mgr = DownloadManager::new();

        let id = mgr.enqueue("https://example.com/files/report.pdf", "application/pdf");
        assert_eq!(mgr.get_task(id).unwrap().file_name, "report.pdf");
        assert_eq!(mgr.get_task(id).unwrap().status, DownloadStatus::Pending);

        mgr.update_progress(id, 0.5);
        assert_eq!(mgr.get_task(id).unwrap().status, DownloadStatus::InProgress);
        assert!((mgr.get_task(id).unwrap().progress - 0.5).abs() < f32::EPSILON);

        mgr.complete(id);
        assert_eq!(mgr.get_task(id).unwrap().status, DownloadStatus::Completed);
        assert!((mgr.get_task(id).unwrap().progress - 1.0).abs() < f32::EPSILON);
    }

    #[test]
    fn active_downloads_filter() {
        let mut mgr = DownloadManager::new();
        let id1 = mgr.enqueue("https://example.com/a.zip", "application/zip");
        let id2 = mgr.enqueue("https://example.com/b.zip", "application/zip");

        mgr.complete(id1);
        let active = mgr.active_downloads();
        assert_eq!(active.len(), 1);
        assert_eq!(active[0].id, id2);
    }

    #[test]
    fn fallback_filename() {
        let mut mgr = DownloadManager::new();
        let id = mgr.enqueue("https://example.com/", "text/html");
        assert_eq!(mgr.get_task(id).unwrap().file_name, format!("download_{}", id));
    }

    #[test]
    fn progress_clamped() {
        let mut mgr = DownloadManager::new();
        let id = mgr.enqueue("https://example.com/x.bin", "application/octet-stream");
        mgr.update_progress(id, 2.5);
        assert!((mgr.get_task(id).unwrap().progress - 1.0).abs() < f32::EPSILON);
    }
}
