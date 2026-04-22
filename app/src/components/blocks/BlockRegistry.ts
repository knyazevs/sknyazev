import type { Component } from "svelte";
import ImageBlock from "./ImageBlock.svelte";
import ImageGalleryBlock from "./ImageGalleryBlock.svelte";
import LinkListBlock from "./LinkListBlock.svelte";
import TextWithImageBlock from "./TextWithImageBlock.svelte";

export type UiBlock =
    | {
          type: "image";
          url: string;
          caption?: string | null;
          alt?: string | null;
          link?: string | null;
      }
    | {
          type: "image_gallery";
          images: Array<{ url: string; caption?: string | null }>;
      }
    | {
          type: "link_list";
          items: Array<{
              url: string;
              title: string;
              description?: string | null;
          }>;
      }
    | {
          type: "text_with_image";
          text: string;
          image: { url: string; alt?: string | null; caption?: string | null };
          imagePosition?: "left" | "right";
      };

export const BLOCK_REGISTRY: Record<UiBlock["type"], Component<any>> = {
    image: ImageBlock,
    image_gallery: ImageGalleryBlock,
    link_list: LinkListBlock,
    text_with_image: TextWithImageBlock,
};
