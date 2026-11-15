import { http } from '@kit.NetworkKit';
import { HttpClient, ApiError } from '../common/HttpClient';
import { FavoriteStatus, FavoriteStatusResponse } from '../models/WordbookModels';

export class FavoriteService {
  static async getStatus(word: string, token: string): Promise<boolean> {
    const path = `/favorites/words/status?word=${encodeURIComponent(word)}`;
    const raw = await HttpClient.getJson(path, token);
    const result = JSON.parse(raw) as FavoriteStatusResponse;
    if (!result || !result.data || result.data.word !== word || typeof result.data.is_favorite !== 'boolean') {
      throw new ApiError(200, '收藏状态格式异常');
    }
    return result.data.is_favorite;
  }

  static async add(movieId: number, word: string, token: string): Promise<void> {
    const body = JSON.stringify({ movie_id: movieId, word: word });
    await HttpClient.request('/favorites/words', http.RequestMethod.PUT, body, {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`
    });
  }

  static async remove(word: string, token: string): Promise<void> {
    const path = `/favorites/words?word=${encodeURIComponent(word)}`;
    await HttpClient.request(path, http.RequestMethod.DELETE, '', {
      'Authorization': `Bearer ${token}`
    });
  }
}