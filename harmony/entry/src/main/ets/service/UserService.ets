import { HttpClient, ApiError } from '../common/HttpClient';
import { CheckinData, CheckinResponse, CheckinDay } from '../models/UserModels';

export class UserService {
  static async getCheckin(token: string): Promise<CheckinData> {
    const raw = await HttpClient.getJson('/user/checkin', token);
    const result = JSON.parse(raw) as CheckinResponse;
    if (!result || !result.data || !Array.isArray(result.data.days) ||
      typeof result.data.streak_days !== 'number' || typeof result.data.week_count !== 'number') {
      throw new ApiError(200, '打卡数据格式异常，请确认后端版本');
    }
    for (let i = 0; i < result.data.days.length; i++) {
      const d: CheckinDay = result.data.days[i];
      if (typeof d.date !== 'string' || typeof d.checked !== 'boolean' || typeof d.is_today !== 'boolean') {
        throw new ApiError(200, '打卡日期字段不完整');
      }
    }
    return result.data;
  }
}