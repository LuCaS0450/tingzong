export function errorCode(message: string, status: number): string {
 if (status === 401) return 'auth_required';
 if (status === 409) return 'duplicate_content';
 if (message.includes('正在清理') || message.includes('预留已失效')) return 'media_retry';
 if (message.includes('100张')) return 'image_limit';
 if (message.includes('存储空间')) return 'storage_limit';
 if (message.includes('关闭') || message.includes('维护')) return 'feature_disabled';
 if (message.includes('账号暂不能')) return 'account_disabled';
 if (message.includes('隐藏')) return 'content_hidden';
 if (message.includes('注销') || message.includes('清理步骤') || message.includes('清理未完成')) return 'deletion_retry';
 if (status >= 500) return 'service_unavailable';
 return 'invalid_request';
}
