export interface WordbookWord {
  id: number;
  word: string;
  meaning: string;
  pos: string | null;
  frequency: number | null;
}

export interface WordbookInfo {
  id: number;
  name: string;
  description: string;
  total: number;
}

export interface WordbookWordListResponse {
  data: WordbookWord[];
  total: number;
  book: WordbookInfo;
}
export interface WordSearchResponse {
  data: WordbookWord[];
  book_id: number;
  has_more: boolean;
  next_offset: number | null;
}
export interface WordSuggestionResponse {
  data: WordbookWord[];
  book_id: number;
}
export interface FavoriteWord {
  id: number;
  word: string;
  meaning: string | null;
  sentence_text: string | null;
  source_movie_id: number | null;
  source_movie_name: string | null;
  start_time: number | null;
  end_time: number | null;
  created_at: string;
}

export interface FavoritePage {
  items: FavoriteWord[];
  next_before_id: number | null;
}

export interface FavoritesResponse {
  code: number;
  data: FavoritePage;
}
export interface FavoriteStatus {
  word: string;
  is_favorite: boolean;
}

export interface FavoriteStatusResponse {
  code: number;
  data: FavoriteStatus;
}
