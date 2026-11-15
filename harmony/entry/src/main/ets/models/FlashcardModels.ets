export interface MovieWord {
  id?: number;
  word: string;
  meaning: string;
  start_time: number;
  end_time: number;
  sentence_text?: string | null;
}
export interface MovieWordCorrection {
  start_time: number;
  end_time: number;
  sentence_text: string;
}
export interface MovieWords { movie_id: number; words: MovieWord[]; }
export interface MovieWordsResponse { data: MovieWords; }
export interface PlaybackTicket { ticket?: string; expires_at?: number; status?: string; }
export interface PlaybackResponse { data: PlaybackTicket; }
