import { callApi } from './training'

/**
 * 数据备份。
 *
 * **没有「恢复」接口，这是刻意的。** 恢复要在应用运行时替换正被打开的库文件，
 * 那得先停后端、换文件、再起来——做成一个按钮就是给用户一个半可靠的恢复路径。
 * 正确做法是关掉应用、把备份文件改名回 `change.db` 放回数据目录，
 * 这条路径由 `info().note` 在界面上写清楚。
 */
export interface Backup {
  path: string
  bytes: number
  at: string
  /** 是否落在「文档」下。false 表示退回了数据目录（两种都该再拷一份到别处）。 */
  inDocuments: boolean
}

export interface BackupInfo {
  dataDir: string
  note: string
}

export function createBackup(): Promise<Backup> {
  return callApi('POST', '/api/backup') as Promise<Backup>
}

export function listBackups(): Promise<Backup[]> {
  return callApi('GET', '/api/backup') as Promise<Backup[]>
}

export function backupInfo(): Promise<BackupInfo> {
  return callApi('GET', '/api/backup/info') as Promise<BackupInfo>
}

/** 人类可读的大小。备份通常是几百 KB 到几十 MB，用 MB 一位小数就够。 */
export function humanSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
