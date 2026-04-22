<script lang="ts">
    interface GalleryImage {
        url: string;
        caption?: string | null;
    }

    interface Props {
        images: GalleryImage[];
    }

    let { images }: Props = $props();
</script>

<div class="gallery-block">
    {#each images as img}
        <figure>
            <img src={img.url} alt={img.caption ?? ""} loading="lazy" />
            {#if img.caption}
                <figcaption>{img.caption}</figcaption>
            {/if}
        </figure>
    {/each}
</div>

<style>
    .gallery-block {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
        gap: 8px;
        max-width: 480px;
    }
    figure {
        margin: 0;
        border-radius: 8px;
        overflow: hidden;
        background: rgba(255, 255, 255, 0.03);
        border: 1px solid rgba(255, 255, 255, 0.06);
    }
    img {
        display: block;
        width: 100%;
        height: 140px;
        object-fit: cover;
    }
    figcaption {
        padding: 4px 8px;
        font-size: 11px;
        color: rgba(255, 255, 255, 0.55);
        line-height: 1.35;
    }
</style>
