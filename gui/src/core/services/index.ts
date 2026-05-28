import apiClient from '@/core/api/api.client';
import { LocationService } from '@/services/LocationService';

export const locationService = new LocationService(apiClient);