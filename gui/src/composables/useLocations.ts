import { ref } from 'vue';
import { locationService } from '@/core/services';
import type { Location } from '@/types/models/location';

export function useLocations() {
    const locations = ref<Location[]>([]);
    const isLoading = ref(false);

    const fetchLocations = async () => {
        isLoading.value = true;
        try {
            const dtos = await locationService.getAllLocations();
            locations.value = dtos;
        } catch (error) {
            console.error('Fetch all locations error:', error);
        } finally {
            isLoading.value = false;
        }
    };

    return {
        locations,
        isLoading,
        fetchLocations,
    };
}