export interface CheckinDay {
  date: string;
  weekday: number;
  checked: boolean;
  is_today: boolean;
}

export interface CheckinData {
  user_id: number;
  today: string;
  days: CheckinDay[];
  week_count: number;
  streak_days: number;
  next_checkin_after_seconds: number;
}

export interface CheckinResponse {
  code: number;
  data: CheckinData;
}