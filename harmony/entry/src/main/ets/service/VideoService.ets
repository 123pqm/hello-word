import { http } from '@kit.NetworkKit';
import { image } from '@kit.ImageKit';
import { HttpClient, ApiError } from '../common/HttpClient';
import { AppConfig } from '../common/AppConfig';
import { MovieHistoryResponse, MovieHistoryPage, MovieHistoryItem, MovieStatusResponse, MovieStatus } from '../models/VideoModels';

export class VideoService {
  static async history(token: string, beforeId: number | null = null): Promise<MovieHistoryPage> {
    const path = '/video/history?limit=20' + (beforeId === null ? '' : `&before_id=${beforeId}`);
    const result = JSON.parse(await HttpClient.getJson(path, token)) as MovieHistoryResponse;
    if (!result.data || !Array.isArray(result.data.items) ||
      (result.data.next_before_id !== null && (!Number.isInteger(result.data.next_before_id) ||
        result.data.next_before_id <= 0 || (beforeId !== null && result.data.next_before_id >= beforeId)))) {
      throw new ApiError(200, '历史记录格式异常');
    }
    for (const item of result.data.items) {
      if (!item || !Number.isInteger(item.id) || item.id <= 0) {
        throw new ApiError(200, '电影记录编号异常');
      }
    }
    return result.data;
  }

  static async deleteMovie(movieId: number, token: string): Promise<void> {
    await HttpClient.request(`/video/${movieId}`, http.RequestMethod.DELETE, '', {
      'Authorization': `Bearer ${token}`
    });
  }

  static async latest(token: string): Promise<MovieHistoryItem | undefined> {
    const raw = await HttpClient.getJson('/video/history?limit=1', token);
    const result = JSON.parse(raw) as MovieHistoryResponse;
    if (!result.data || !Array.isArray(result.data.items)) {
      throw new ApiError(200, '历史记录格式异常，请确认后端版本');
    }
    const item = result.data.items[0];
    if (item && (!Number.isInteger(item.id) || item.id <= 0 || typeof item.file_name !== 'string' ||
      typeof item.status !== 'string' || typeof item.created_at !== 'string')) {
      throw new ApiError(200, '电影记录字段不完整');
    }
    return item;
  }

  static async status(movieId: number, token: string): Promise<MovieStatus> {
    const raw = await HttpClient.getJson(`/video/${movieId}/status`, token);
    const result = JSON.parse(raw) as MovieStatusResponse;
    if (!result.data || result.data.movie_id !== movieId || typeof result.data.status !== 'string' ||
      typeof result.data.matched_word_count !== 'number') {
      throw new ApiError(200, '任务状态格式异常');
    }
    return result.data;
  }

  static async cover(movieId: number, token: string): Promise<image.PixelMap> {
    const request = http.createHttp();
    try {
      const response = await request.request(`${AppConfig.BASE_URL}/video/${movieId}/cover`, {
        method: http.RequestMethod.GET,
        header: { 'Authorization': `Bearer ${token}` },
        expectDataType: http.HttpDataType.ARRAY_BUFFER,
        connectTimeout: AppConfig.REQUEST_TIMEOUT,
        readTimeout: AppConfig.REQUEST_TIMEOUT,
        usingCache: false
      });
      if (response.responseCode !== 200) {
        throw new ApiError(response.responseCode, '封面暂不可用');
      }
      const source = image.createImageSource(response.result as ArrayBuffer);
      try {
        return await source.createPixelMap({ desiredSize: { width: 176, height: 232 } });
      } finally {
        source.release();
      }
    } finally {
      request.destroy();
    }
  }
}
