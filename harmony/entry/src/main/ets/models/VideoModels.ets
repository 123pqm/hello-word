export interface MovieHistoryItem {
  id: number;
  file_name: string;
  status: string;
  created_at: string;
}
export interface MovieHistoryPage {
  items: MovieHistoryItem[];
  next_before_id: number | null;
}
export interface MovieHistoryResponse { data: MovieHistoryPage; }
export interface MovieStatus {
  movie_id: number;
  status: string;
  error_message: string | null;
  matched_word_count: number;
}
export interface MovieStatusResponse { data: MovieStatus; }
