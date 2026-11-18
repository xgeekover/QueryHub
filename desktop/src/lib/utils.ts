import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

/** 클래스 이름을 합치고 Tailwind 의 충돌하는 클래스는 뒤의 것만 남긴다 (shadcn/ui 의 cn). */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}
