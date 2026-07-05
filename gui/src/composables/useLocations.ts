import { ref } from 'vue';
import { locationService } from '@/core/services';
import type { Location } from '@/types/models/location';

const RADIUS_METERS = 2000; // 2 km

export function useLocations() {
    const locations = ref<Location[]>([]);
    const isLoading = ref(false);
    const currentPosition = ref<{ latitude: number; longitude: number } | null>(null);
    let watchId: number | null = null;

    const fetchNearbyLocations = async (latitude: number, longitude: number) => {
        isLoading.value = true;
        try {
            const dtos = await locationService.getNearbyLocations(latitude, longitude, RADIUS_METERS);
            locations.value = dtos;
        } catch (error) {
            console.error('Fetch nearby locations error:', error);
        } finally {
            isLoading.value = false;
        }
    };

    const startLocationWatch = () => {
        if (!navigator.geolocation || watchId !== null) {
            return;
        }

        watchId = navigator.geolocation.watchPosition(
            async (position) => {
                const { latitude, longitude } = position.coords;
                currentPosition.value = { latitude, longitude };
                await fetchNearbyLocations(latitude, longitude);
            },
            (error) => {
                console.error('Geolocation watch error:', error.message);
            },
            {
                enableHighAccuracy: true,
                timeout: 10000,
                maximumAge: 0,
            }
        );
    };

    const stopLocationWatch = () => {
        if (watchId !== null) {
            navigator.geolocation.clearWatch(watchId);
            watchId = null;
        }
    };

    return {
        locations,
        isLoading,
        currentPosition,
        startLocationWatch,
        stopLocationWatch,
        fetchNearbyLocations,
    };
}